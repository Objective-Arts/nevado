package org.skyscreamer.nevado.jms.connector.amazonaws;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.skyscreamer.nevado.jms.NevadoConnectionFactory;
import org.skyscreamer.nevado.jms.connector.SQSMessage;
import org.skyscreamer.nevado.jms.destination.NevadoQueue;
import org.skyscreamer.nevado.jms.destination.NevadoTopic;
import org.skyscreamer.nevado.jms.message.JMSXProperty;
import org.skyscreamer.nevado.jms.message.NevadoMessage;
import org.skyscreamer.nevado.jms.message.NevadoTextMessage;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Runs the AWS connector against a local stub endpoint ({@link StubAwsEndpoint}) and compares what goes over the
 * wire with what the AWS SDK v1 connector sent (<code>aws-stub/v1-wire.txt</code>, recorded with this same test on
 * the v1 code by running it with <code>-Dnevado.aws.recordWire=&lt;file&gt;</code>). Both the requests (action,
 * queue URL, body, attributes, ...) and what the connector makes of the answers (ids, ARNs, JMSException types and
 * texts) are compared.
 */
public class AmazonAwsSQSConnectorStubTest {
    private static final String ACCESS_KEY = "AKIDSTUBEXAMPLE";
    private static final String SECRET_KEY = "stub/secret/key";
    // Characters that the Query (form) and JSON encodings treat differently
    private static final String BODY = "héllo & <x> + 100% = \"q\" 'a'\n\tline2 日本 😀 a=b&c=d \\ /";

    private StubAwsEndpoint _stub;
    private final List<String> _log = new ArrayList<String>();

    /** Exposes what the connector inherits from AbstractSQSConnector, for the test. */
    static class TestConnector extends AmazonAwsSQSConnector {
        TestConnector(String endpoint) {
            super(ACCESS_KEY, SECRET_KEY, endpoint, endpoint, false, 200);
        }

        String serialize(NevadoMessage message) throws JMSException {
            return serializeMessage(message);
        }

        AmazonAwsSQSQueue queue(NevadoQueue queue) throws JMSException {
            return (AmazonAwsSQSQueue) getSQSQueue(queue);
        }

        NevadoMessage convert(NevadoQueue queue, SQSMessage message) throws JMSException {
            return convertSqsMessage(queue, message, false);
        }
    }

    @Before
    public void setUp() throws IOException {
        _stub = new StubAwsEndpoint();
    }

    @After
    public void tearDown() {
        _stub.stop();
    }

    @Test
    public void testWireFormatMatchesV1() throws Exception {
        String endpoint = _stub.getEndpoint();
        TestConnector connector = new TestConnector(endpoint);

        connector.test();
        flush("test()");

        NevadoQueue nevadoQueue = new NevadoQueue("nevado-stub-queue");
        AmazonAwsSQSQueue queue = connector.queue(nevadoQueue);
        flush("getSQSQueue");
        result("queueUrl", nevadoQueue.getQueueUrl());
        connector.queue(nevadoQueue);
        flush("getSQSQueue again (url known)");

        result("messageId", queue.sendMessage(BODY));
        flush("sendMessage");

        NevadoTextMessage textMessage = new NevadoTextMessage();
        textMessage.setText(BODY);
        textMessage.setJMSMessageID("ID:fixed-id");
        textMessage.setStringProperty("color", "blue");
        String serialized = connector.serialize(textMessage);
        result("serialized", serialized);

        AmazonAwsSQSMessage first = queue.receiveMessage();
        flush("receiveMessage");
        result("received", first.getMessageId() + " " + first.getReceiptHandle() + " " + first.getAttributes() + " "
                + first.getMessageBody().equals(BODY));
        queue.deleteMessage(first.getReceiptHandle());
        flush("deleteMessage");

        _stub.enqueue(nevadoQueue.getQueueUrl(), serialized);
        AmazonAwsSQSMessage second = queue.receiveMessage();
        flush("receiveMessage (nevado body)");
        NevadoMessage converted = connector.convert(nevadoQueue, second);
        result("converted", converted.getClass().getSimpleName() + " " + converted.getJMSMessageID() + " "
                + ((TextMessage) converted).getText().equals(BODY) + " " + converted.getStringProperty("color") + " "
                + converted.getObjectProperty(JMSXProperty.JMSXDeliveryCount + ""));
        queue.setMessageVisibilityTimeout(second.getReceiptHandle(), 0);
        flush("setMessageVisibilityTimeout");
        AmazonAwsSQSMessage third = queue.receiveMessage();
        result("redelivered", third.getMessageId() + " " + third.getAttributes());
        queue.deleteMessage(third.getReceiptHandle());
        result("empty", String.valueOf(queue.receiveMessage()));
        flush("receive, delete, receive");

        result("queueArn", queue.getQueueARN());
        flush("getQueueARN");

        connector.createQueue("nevado-temp-one");
        connector.createQueue("nevado-temp-two");
        flush("createQueue x2");
        result("listQueues", names(connector.listQueues("nevado-temp-")));
        flush("listQueues(prefix)");

        NevadoTopic topic = connector.createTopic("nevado-stub-topic");
        flush("createTopic");
        result("topicArn", topic.getArn());
        connector.sendSNSMessage(topic, BODY);
        flush("sendSNSMessage");
        result("listTopics", names(connector.listTopics()));
        flush("listTopics");
        NevadoQueue endpointQueue = new NevadoQueue("nevado-stub-endpoint");
        String subscriptionArn = connector.subscribe(topic, endpointQueue);
        flush("subscribe");
        result("subscriptionArn", subscriptionArn);
        connector.unsubscribe(new NevadoTopic(topic, endpointQueue, subscriptionArn, true));
        flush("unsubscribe");
        connector.deleteTopic(topic);
        flush("deleteTopic");

        connector.deleteQueue(nevadoQueue);
        flush("deleteQueue");

        _stub.failWith("ListQueues", 403, "InvalidClientTokenId", "The security token included in the request is invalid.");
        failure(connector, "test() with a bad key", () -> connector.test());
        _stub.failWith("SendMessage", 400, "AWS.SimpleQueueService.NonExistentQueue",
                "The specified queue does not exist for this wsdl version.");
        failure(connector, "sendMessage to a missing queue", () -> queue.sendMessage("x"));
        _stub.failWith("Publish", 403, "AuthorizationError", "User is not authorized to perform: SNS:Publish");
        failure(connector, "sendSNSMessage not authorized", () -> connector.sendSNSMessage(topic, "x"));
        _stub.noFailures();

        compare(String.join("\n", _log) + "\n");
    }

    @Test
    public void testUnknownHostIsResourceAllocation() {
        TestConnector connector = new TestConnector("http://a.deliberately.invalid.server");
        try {
            connector.test();
            Assert.fail("expected an exception");
        } catch (JMSException e) {
            Assert.assertEquals(jakarta.jms.ResourceAllocationException.class, e.getClass());
            Assert.assertTrue(e.getMessage(), e.getMessage().startsWith(
                    "Connection test failed: Unable to execute HTTP request: a.deliberately.invalid.server"));
        }
    }

    @Test
    public void testJmsRoundTripThroughFactory() throws Exception {
        AmazonAwsSQSConnectorFactory connectorFactory = new AmazonAwsSQSConnectorFactory();
        connectorFactory.setSecure(false);
        NevadoConnectionFactory connectionFactory = new NevadoConnectionFactory(connectorFactory);
        connectionFactory.setAwsAccessKey(ACCESS_KEY);
        connectionFactory.setAwsSecretKey(SECRET_KEY);
        connectionFactory.setAwsSQSEndpoint(_stub.getEndpoint());
        connectionFactory.setAwsSNSEndpoint(_stub.getEndpoint());
        Connection connection = connectionFactory.createConnection();
        try {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("nevado-roundtrip");
            MessageProducer producer = session.createProducer(queue);
            TextMessage sent = session.createTextMessage(BODY);
            sent.setStringProperty("color", "blue");
            producer.send(sent);
            MessageConsumer consumer = session.createConsumer(queue);
            Message received = consumer.receive(5000);
            Assert.assertNotNull(received);
            Assert.assertEquals(BODY, ((TextMessage) received).getText());
            Assert.assertEquals("blue", received.getStringProperty("color"));
            Assert.assertEquals(sent.getJMSMessageID(), received.getJMSMessageID());
            Assert.assertEquals(1, received.getIntProperty(JMSXProperty.JMSXDeliveryCount + ""));
        } finally {
            connection.close();
        }
        boolean deleted = false;
        for (String request : _stub.getRequests()) {
            deleted |= request.startsWith("SQS DeleteMessage");
        }
        Assert.assertTrue("message was not deleted after AUTO_ACKNOWLEDGE", deleted);
    }

    interface Call {
        void run() throws Exception;
    }

    private void failure(AmazonAwsSQSConnector connector, String label, Call call) throws Exception {
        try {
            call.run();
            result("failure " + label, "no exception");
        } catch (JMSException e) {
            result("failure " + label, e.getClass().getName() + ": " + e.getMessage());
        }
        flush(label);
    }

    private static String names(Collection<?> destinations) {
        TreeSet<String> names = new TreeSet<String>();
        for (Object destination : destinations) {
            names.add(destination instanceof NevadoTopic ? ((NevadoTopic) destination).getTopicName() + " arn=" + ((NevadoTopic) destination).getArn()
                    : destination instanceof NevadoQueue ? ((NevadoQueue) destination).getQueueUrl() + " (" + ((NevadoQueue) destination).getQueueName() + ")"
                    : String.valueOf(destination));
        }
        return names.toString();
    }

    private void result(String name, String value) {
        _log.add("=> " + name + ": " + portless(String.valueOf(value)));
    }

    private void flush(String label) {
        _log.add("## " + label);
        for (String request : _stub.getRequests()) {
            _log.add(portless(request));
        }
        _stub.clearRequests();
    }

    private String portless(String s) {
        return s.replace(_stub.getEndpoint(), "http://STUB");
    }

    private void compare(String actual) throws IOException {
        String record = System.getProperty("nevado.aws.recordWire");
        if (record != null && !record.isEmpty()) {
            Files.write(new File(record).toPath(), actual.getBytes(StandardCharsets.UTF_8));
            return;
        }
        InputStream in = getClass().getResourceAsStream("/aws-stub/v1-wire.txt");
        Assert.assertNotNull("aws-stub/v1-wire.txt is missing", in);
        String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        in.close();
        Assert.assertEquals(expected, actual);
    }
}
