package org.skyscreamer.nevado.jms.connector.amazonaws;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.codec.digest.DigestUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tiny local SQS + SNS endpoint for the AWS connector tests. It understands both the AWS Query protocol (form
 * posts, XML answers: SDK v1 SQS and SNS, SDK v2 SNS) and the AWS JSON protocol (SDK v2 SQS), and records every
 * request in one protocol-independent form: the action plus its parameters named as in the Query protocol
 * (<code>AttributeName.1</code>, <code>Attribute.1.Name</code>, ...). v1 addresses SQS requests to the queue URL
 * instead of a <code>QueueUrl</code> parameter: that is recorded as the <code>QueueUrl</code> parameter too.
 * SQS works as a real queue store: sent messages are handed out again on ReceiveMessage until they are deleted.
 */
public class StubAwsEndpoint {
    public static final String ACCOUNT = "123456789012";

    private final HttpServer _server;
    private final List<String> _requests = Collections.synchronizedList(new ArrayList<String>());
    private final Map<String, LinkedList<String[]>> _queues = new HashMap<String, LinkedList<String[]>>();
    private final Map<String, String> _inFlight = new HashMap<String, String>();
    private int _messageCounter = 0;
    private volatile String _failAction = null;
    private volatile int _failStatus;
    private volatile String _failCode;
    private volatile String _failMessage;

    public StubAwsEndpoint() throws IOException {
        _server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        _server.createContext("/", this::handle);
        _server.start();
    }

    public String getEndpoint() {
        return "http://127.0.0.1:" + _server.getAddress().getPort();
    }

    public String queueUrl(String queueName) {
        return getEndpoint() + "/" + ACCOUNT + "/" + queueName;
    }

    public void stop() {
        _server.stop(0);
    }

    public List<String> getRequests() {
        synchronized (_requests) {
            return new ArrayList<String>(_requests);
        }
    }

    public void clearRequests() {
        _requests.clear();
    }

    /** Answer every request for the given action (or every request if <code>action</code> is "*") with an error. */
    public void failWith(String action, int status, String code, String message) {
        _failStatus = status;
        _failCode = code;
        _failMessage = message;
        _failAction = action;
    }

    public void noFailures() {
        _failAction = null;
    }

    /** Put a message in a queue as if another application had sent it. */
    public synchronized void enqueue(String queueUrl, String body) {
        String id = "stub-msg-" + (++_messageCounter);
        queue(queueUrl).add(new String[] {id, body, "0"});
    }

    private LinkedList<String[]> queue(String queueUrl) {
        LinkedList<String[]> queue = _queues.get(queueUrl);
        if (queue == null) {
            queue = new LinkedList<String[]>();
            _queues.put(queueUrl, queue);
        }
        return queue;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String body = read(exchange.getRequestBody());
            String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
            boolean json = target != null;
            String service;
            Map<String, String> params;
            if (json) {
                service = target.startsWith("AmazonSQS.") ? "SQS" : target.substring(0, target.indexOf('.'));
                params = flattenJson(new JSONObject(body.isEmpty() ? "{}" : body));
                params.put("Action", target.substring(target.indexOf('.') + 1));
            } else {
                params = parseForm(body);
                params.remove("Version");
                String path = exchange.getRequestURI().getPath();
                if (path != null && path.length() > 1 && !params.containsKey("QueueUrl")) {
                    params.put("QueueUrl", getEndpoint() + path);
                }
                service = isSnsAction(params.get("Action")) ? "SNS" : "SQS";
            }
            String action = params.remove("Action");
            _requests.add(describe(service, action, params));

            if (_failAction != null && ("*".equals(_failAction) || _failAction.equals(action))) {
                sendError(exchange, json, service);
                return;
            }
            Map<String, Object> result = answer(action, params);
            if (json) {
                send(exchange, 200, "application/x-amz-json-1.0", toJson(result).toString());
            } else {
                send(exchange, 200, "text/xml", toXml(service, action, result));
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            send(exchange, 500, "text/plain", String.valueOf(e));
        } finally {
            exchange.close();
        }
    }

    private static boolean isSnsAction(String action) {
        return "CreateTopic".equals(action) || "Publish".equals(action) || "Subscribe".equals(action)
                || "Unsubscribe".equals(action) || "ListTopics".equals(action) || "DeleteTopic".equals(action);
    }

    static String describe(String service, String action, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(service).append(' ').append(action);
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(params).entrySet()) {
            sb.append("\n    ").append(entry.getKey()).append('=').append(escape(entry.getValue()));
        }
        return sb.toString();
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    // The answers, as Query-protocol-shaped values: String, List<String> (members) or Map (Attribute entries)
    private synchronized Map<String, Object> answer(String action, Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String queueUrl = params.get("QueueUrl");
        if ("CreateQueue".equals(action)) {
            String url = queueUrl(params.get("QueueName"));
            queue(url);
            result.put("QueueUrl", url);
        } else if ("ListQueues".equals(action)) {
            List<String> urls = new ArrayList<String>();
            String prefix = params.get("QueueNamePrefix");
            for (String url : new TreeMap<String, Object>(_queues).keySet()) {
                String name = url.substring(url.lastIndexOf('/') + 1);
                if (prefix == null || name.startsWith(prefix)) {
                    urls.add(url);
                }
            }
            result.put("QueueUrl", urls);
        } else if ("SendMessage".equals(action)) {
            String id = "stub-msg-" + (++_messageCounter);
            String body = params.get("MessageBody");
            queue(queueUrl).add(new String[] {id, body, "0"});
            result.put("MD5OfMessageBody", DigestUtils.md5Hex(body.getBytes(StandardCharsets.UTF_8)));
            result.put("MessageId", id);
        } else if ("ReceiveMessage".equals(action)) {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            for (String[] message : queue(queueUrl)) {
                if (!_inFlight.containsValue(message[0])) {
                    int count = Integer.parseInt(message[2]) + 1;
                    message[2] = String.valueOf(count);
                    String receiptHandle = "rh-" + message[0] + "-" + count;
                    _inFlight.put(receiptHandle, message[0]);
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("MessageId", message[0]);
                    m.put("ReceiptHandle", receiptHandle);
                    m.put("MD5OfBody", DigestUtils.md5Hex(message[1].getBytes(StandardCharsets.UTF_8)));
                    m.put("Body", message[1]);
                    if (params.containsValue("ApproximateReceiveCount") || params.containsValue("All")) {
                        m.put("Attribute", Collections.singletonMap("ApproximateReceiveCount", String.valueOf(count)));
                    }
                    messages.add(m);
                    break;
                }
            }
            result.put("Message", messages);
        } else if ("DeleteMessage".equals(action)) {
            String id = _inFlight.remove(params.get("ReceiptHandle"));
            for (Iterator<String[]> it = queue(queueUrl).iterator(); it.hasNext(); ) {
                if (it.next()[0].equals(id)) {
                    it.remove();
                }
            }
        } else if ("ChangeMessageVisibility".equals(action)) {
            if ("0".equals(params.get("VisibilityTimeout"))) {
                _inFlight.remove(params.get("ReceiptHandle"));
            }
        } else if ("GetQueueAttributes".equals(action)) {
            String name = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
            result.put("Attribute", Collections.singletonMap("QueueArn", "arn:aws:sqs:us-east-1:" + ACCOUNT + ":" + name));
        } else if ("DeleteQueue".equals(action)) {
            _queues.remove(queueUrl);
        } else if ("CreateTopic".equals(action)) {
            result.put("TopicArn", "arn:aws:sns:us-east-1:" + ACCOUNT + ":" + params.get("Name"));
        } else if ("Publish".equals(action)) {
            result.put("MessageId", "stub-sns-" + (++_messageCounter));
        } else if ("Subscribe".equals(action)) {
            result.put("SubscriptionArn", params.get("TopicArn") + ":stub-subscription");
        } else if ("ListTopics".equals(action)) {
            List<Map<String, Object>> topics = new ArrayList<Map<String, Object>>();
            topics.add(Collections.<String, Object>singletonMap("TopicArn", "arn:aws:sns:us-east-1:" + ACCOUNT + ":stub-topic-a"));
            topics.add(Collections.<String, Object>singletonMap("TopicArn", "arn:aws:sns:us-east-1:" + ACCOUNT + ":stub-topic-b"));
            result.put("Topics", topics);
        }
        return result;
    }

    private void sendError(HttpExchange exchange, boolean json, String service) throws IOException {
        exchange.getResponseHeaders().add("x-amzn-RequestId", "stub-request-id");
        if (json) {
            exchange.getResponseHeaders().add("x-amzn-query-error", _failCode + ";Sender");
            JSONObject error = new JSONObject();
            error.put("__type", "com.amazonaws.sqs#" + _failCode);
            error.put("message", _failMessage);
            send(exchange, _failStatus, "application/x-amz-json-1.0", error.toString());
        } else {
            String xml = "<ErrorResponse xmlns=\"" + namespace(service) + "\"><Error><Type>Sender</Type><Code>" + _failCode
                    + "</Code><Message>" + xmlEscape(_failMessage) + "</Message><Detail/></Error>"
                    + "<RequestId>stub-request-id</RequestId></ErrorResponse>";
            send(exchange, _failStatus, "text/xml", xml);
        }
    }

    private static String namespace(String service) {
        return "SNS".equals(service) ? "http://sns.amazonaws.com/doc/2010-03-31/" : "http://queue.amazonaws.com/doc/2012-11-05/";
    }

    @SuppressWarnings("unchecked")
    private static String toXml(String service, String action, Map<String, Object> result) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>");
        sb.append('<').append(action).append("Response xmlns=\"").append(namespace(service)).append("\">");
        sb.append('<').append(action).append("Result>");
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            appendXml(sb, entry.getKey(), entry.getValue(), "SNS".equals(service));
        }
        sb.append("</").append(action).append("Result>");
        sb.append("<ResponseMetadata><RequestId>stub-request-id</RequestId></ResponseMetadata>");
        sb.append("</").append(action).append("Response>");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendXml(StringBuilder sb, String name, Object value, boolean sns) {
        if (value instanceof List) {
            if (sns) sb.append('<').append(name).append('>');
            for (Object item : (List<Object>) value) {
                appendXml(sb, sns ? "member" : name, item, sns);
            }
            if (sns) sb.append("</").append(name).append('>');
        } else if (value instanceof Map && "Attribute".equals(name)) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                sb.append("<Attribute><Name>").append(entry.getKey()).append("</Name><Value>")
                        .append(xmlEscape(String.valueOf(entry.getValue()))).append("</Value></Attribute>");
            }
        } else if (value instanceof Map) {
            sb.append('<').append(name).append('>');
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                appendXml(sb, entry.getKey(), entry.getValue(), sns);
            }
            sb.append("</").append(name).append('>');
        } else {
            sb.append('<').append(name).append('>').append(xmlEscape(String.valueOf(value))).append("</").append(name).append('>');
        }
    }

    // JSON protocol names: lists are plural (QueueUrls, Messages), attribute maps are "Attributes"
    @SuppressWarnings("unchecked")
    private static JSONObject toJson(Map<String, Object> result) {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof List) {
                JSONArray array = new JSONArray();
                for (Object item : (List<Object>) value) {
                    array.put(item instanceof Map ? toJson((Map<String, Object>) item) : item);
                }
                json.put(name + "s", array);
            } else if (value instanceof Map && "Attribute".equals(name)) {
                json.put("Attributes", new JSONObject((Map<String, Object>) value));
            } else {
                json.put(name, value);
            }
        }
        return json;
    }

    // JSON request members, renamed to their Query protocol names
    private static Map<String, String> flattenJson(JSONObject json) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (String key : json.keySet()) {
            Object value = json.get(key);
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                String member = key.endsWith("s") ? key.substring(0, key.length() - 1) : key;
                for (int i = 0; i < array.length(); i++) {
                    params.put(member + "." + (i + 1), String.valueOf(array.get(i)));
                }
            } else if (value instanceof JSONObject && "Attributes".equals(key)) {
                JSONObject map = (JSONObject) value;
                int i = 0;
                for (String name : new TreeMap<String, Object>(map.toMap()).keySet()) {
                    i++;
                    params.put("Attribute." + i + ".Name", name);
                    params.put("Attribute." + i + ".Value", String.valueOf(map.get(name)));
                }
            } else {
                params.put(key, String.valueOf(value));
            }
        }
        return params;
    }

    private static Map<String, String> parseForm(String body) throws IOException {
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            params.put(key, value);
        }
        return params;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("\r", "&#13;");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        if (!exchange.getResponseHeaders().containsKey("x-amzn-RequestId")) {
            exchange.getResponseHeaders().add("x-amzn-RequestId", "stub-request-id");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }
}
