package org.skyscreamer.nevado.jms.connector.amazonaws;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sns.model.ListTopicsResponse;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;
import org.apache.commons.lang.StringUtils;
import org.skyscreamer.nevado.jms.connector.AbstractSQSConnector;
import org.skyscreamer.nevado.jms.connector.SQSMessage;
import org.skyscreamer.nevado.jms.connector.SQSQueue;
import org.skyscreamer.nevado.jms.destination.NevadoDestination;
import org.skyscreamer.nevado.jms.destination.NevadoQueue;
import org.skyscreamer.nevado.jms.destination.NevadoTopic;
import org.skyscreamer.nevado.jms.message.JMSXProperty;
import org.skyscreamer.nevado.jms.message.NevadoMessage;

import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.ResourceAllocationException;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connector for SQS-only implementation of the Nevado JMS driver.
 *
 * @author Carter Page <carter@skyscreamer.org>
 */
public class AmazonAwsSQSConnector extends AbstractSQSConnector {
    public static final String MESSAGE_ATTRIBUTE_APPROXIMATE_RECEIVE_COUNT = "ApproximateReceiveCount";

    private final SqsClient _amazonSQS;
    private final SnsClient _amazonSNS;
    private final ExecutorService _sqsExecutor;
    private final ExecutorService _snsExecutor;
    private final String _proxyHost;

    private boolean _testAlwaysPasses = false;


    public AmazonAwsSQSConnector(String awsAccessKey, String awsSecretKey, String awsSQSEndpoint, String awsSNSEndpoint, boolean isSecure, long receiveCheckIntervalMs) {
        this(awsAccessKey, awsSecretKey, awsSQSEndpoint, awsSNSEndpoint, isSecure, receiveCheckIntervalMs, false);
    }

    public AmazonAwsSQSConnector(String awsAccessKey, String awsSecretKey, String awsSQSEndpoint, String awsSNSEndpoint, boolean isSecure, long receiveCheckIntervalMs, boolean isAsync) {
        super(receiveCheckIntervalMs, isAsync);
        // Proxy as AWS SDK v1's ClientConfiguration resolved it: http.proxyHost/http.proxyPort if set, otherwise the
        // standard proxy system properties of the protocol in use.
        String proxyHost = System.getProperty("http.proxyHost");
        String proxyPort = System.getProperty("http.proxyPort");
        if (proxyHost == null && isSecure) {
            proxyHost = System.getProperty("https.proxyHost");
            proxyPort = System.getProperty("https.proxyPort");
        }
        _proxyHost = proxyHost;
        AwsCredentialsProvider credentialsProvider;
        if(StringUtils.isNotEmpty(awsAccessKey) && StringUtils.isNotEmpty(awsSecretKey)) {
            credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(awsAccessKey, awsSecretKey));
        }
        else {
            credentialsProvider = InstanceProfileCredentialsProvider.create();
        }
        _amazonSQS = configure(SqsClient.builder(), credentialsProvider, awsSQSEndpoint, "sqs", isSecure, proxyHost, proxyPort).build();
        _amazonSNS = configure(SnsClient.builder(), credentialsProvider, awsSNSEndpoint, "sns", isSecure, proxyHost, proxyPort).build();
        if (isAsync) {
            // AWS SDK v1's async clients: the synchronous client run on a single-thread executor, result not awaited
            _sqsExecutor = Executors.newSingleThreadExecutor();
            _snsExecutor = Executors.newSingleThreadExecutor();
        }
        else {
            _sqsExecutor = null;
            _snsExecutor = null;
        }
    }

    private static <B extends AwsClientBuilder<B, ?> & AwsSyncClientBuilder<B, ?>> B configure(
            B builder, AwsCredentialsProvider credentialsProvider, String endpoint, String service, boolean isSecure,
            String proxyHost, String proxyPort) {
        ProxyConfiguration.Builder proxy = ProxyConfiguration.builder()
                .useSystemPropertyValues(false)
                .useEnvironmentVariableValues(false);
        if (proxyHost != null) {
            proxy.endpoint(URI.create("http://" + proxyHost + (proxyPort != null ? ":" + Integer.parseInt(proxyPort) : "")));
            String nonProxyHosts = System.getProperty("http.nonProxyHosts");
            if (nonProxyHosts != null) {
                for (String host : nonProxyHosts.split("\\|")) {
                    if (!host.trim().isEmpty()) {
                        proxy.addNonProxyHost(host.trim());
                    }
                }
            }
        }
        // AWS SDK v1 defaults: 10 s to connect, 50 s socket timeout, 50 connections, 3 retries
        builder.httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofSeconds(50))
                        .maxConnections(50)
                        .proxyConfiguration(proxy.build()))
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryStrategy(RetryMode.LEGACY).build())
                .credentialsProvider(credentialsProvider);
        if (endpoint != null) {
            URI uri = toURI(endpoint, isSecure);
            builder.endpointOverride(uri).region(Region.of(signingRegion(uri.getHost(), service)));
        }
        else {
            builder.region(Region.US_EAST_1);
        }
        return builder;
    }

    // As AWS SDK v1 (AmazonWebServiceClient.toURI): an endpoint without a scheme gets the configured protocol's
    private static URI toURI(String endpoint, boolean isSecure) {
        if (!endpoint.contains("://")) {
            endpoint = (isSecure ? "https" : "http") + "://" + endpoint;
        }
        return URI.create(endpoint.trim());
    }

    // As AWS SDK v1 (AwsHostNameUtils.parseRegionName): the region an endpoint is signed for, us-east-1 by default
    static String signingRegion(String host, String service) {
        if (host == null) {
            return "us-east-1";
        }
        if (host.endsWith(".amazonaws.com")) {
            String fragment = host.substring(0, host.length() - ".amazonaws.com".length());
            int index = fragment.lastIndexOf('.');
            if (index == -1) {
                return "us-east-1";
            }
            String region = fragment.substring(index + 1);
            return "us-gov".equals(region) ? "us-gov-west-1" : region;
        }
        Matcher matcher = Pattern.compile("^(?:.+\\.)?" + Pattern.quote(service) + "[.-]([a-z0-9-]+)\\.").matcher(host);
        return matcher.find() ? matcher.group(1) : "us-east-1";
    }

    public boolean isTestAlwaysPasses() {
        return _testAlwaysPasses;
    }

    public void setTestAlwaysPasses(boolean _testAlwaysPasses) {
        this._testAlwaysPasses = _testAlwaysPasses;
    }

    @Override
    protected void sendSNSMessage(NevadoTopic topic, String serializedMessage) throws JMSException {
        String arn = getTopicARN(topic);
        final PublishRequest request = PublishRequest.builder().topicArn(arn).message(serializedMessage).build();
        try {
            if (isAsync()) {
                _snsExecutor.submit(() -> _amazonSNS.publish(request));
            } else {
                _amazonSNS.publish(request);
            }
        }
        catch (SdkException e) {
            throw handleAWSException("Unable to send message to topic: " + arn, e);
        }
    }

    @Override
    protected AmazonAwsSQSQueue getSQSQueueImpl(NevadoQueue queue) throws JMSException {
        try {
            if (queue.getQueueUrl() == null)
            {
                CreateQueueResponse result = _amazonSQS.createQueue(CreateQueueRequest.builder().queueName(queue.getQueueName()).build());
                queue.setQueueUrl(result.queueUrl());
            }
        } catch (SdkException e) {
            throw handleAWSException("Unable to get message queue '" + queue, e);
        }

        return new AmazonAwsSQSQueue(this, queue.getQueueUrl(), isAsync());
    }

    @Override
    public void test() throws JMSException {
        if (isTestAlwaysPasses()) {
            return;
        }
        try {
            _amazonSQS.listQueues();
            _amazonSNS.listTopics();
        } catch (SdkException e) {
            throw handleAWSException("Connection test failed", e);
        }
    }

    @Override
    public Collection<NevadoQueue> listQueues(String temporaryQueuePrefix) throws JMSException {
        Collection<NevadoQueue> queues;
        ListQueuesResponse result;
        try {
            result = _amazonSQS.listQueues(ListQueuesRequest.builder().queueNamePrefix(temporaryQueuePrefix).build());
        } catch (SdkException e) {
            throw handleAWSException("Unable to list queues with prefix '" + temporaryQueuePrefix + "'", e);
        }
        queues = new HashSet<NevadoQueue>(result.queueUrls().size());
        for(String queueUrlString : result.queueUrls()) {
            URL queueURL = null;
            try {
                queueURL = new URL(queueUrlString);
            } catch (MalformedURLException e) {
                throw new JMSException("Unable to parse URL for: " + queueUrlString);
            }
            queues.add(new NevadoQueue(queueURL));
        }
        return queues;
    }

    @Override
    public NevadoTopic createTopic(String topicName) throws JMSException {
        NevadoTopic topic = new NevadoTopic(topicName);
        getTopicARN(topic);
        return topic;
    }

    @Override
    public void deleteTopic(NevadoTopic topic) throws JMSException {
        try {
            _amazonSNS.deleteTopic(DeleteTopicRequest.builder().topicArn(getTopicARN(topic)).build());
        } catch (SdkException e) {
            throw handleAWSException("Unable to delete message topic '" + topic, e);
        }
    }

    @Override
    public Collection<NevadoTopic> listTopics() throws JMSException {
        Collection<NevadoTopic> topics;
        ListTopicsResponse result;
        try {
            result = _amazonSNS.listTopics();
        } catch (SdkException e) {
            throw handleAWSException("Unable to list topics", e);
        }
        topics = new HashSet<NevadoTopic>(result.topics().size());
        for(Topic topic : result.topics()) {
            topics.add(new NevadoTopic(topic.topicArn()));
        }
        return topics;
    }

    @Override
    public String subscribe(NevadoTopic topic, NevadoQueue topicEndpoint) throws JMSException {
        String subscriptionArn;
        try {
            SQSQueue queue = getSQSQueue((NevadoDestination) topicEndpoint);
            String sqsArn = queue.getQueueARN();
            String snsArn = getTopicARN(topic);
            queue.setPolicy(getPolicy(snsArn, sqsArn));
            subscriptionArn = _amazonSNS.subscribe(SubscribeRequest.builder().topicArn(getTopicARN(topic))
                    .protocol("sqs").endpoint(sqsArn).build()).subscriptionArn();
        } catch (SdkException e) {
            throw handleAWSException("Unable to subscripe to topic " + topic, e);
        }
        return subscriptionArn;
    }

    @Override
    public void unsubscribe(NevadoTopic topic) throws JMSException {
        if (topic == null) {
            throw new NullPointerException();
        }
        if (topic.getSubscriptionArn() == null) {
            throw new IllegalArgumentException("Topic doesn't have a subscription");
        }
        try {
            _amazonSNS.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(topic.getSubscriptionArn()).build());
        } catch (SdkException e) {
            throw handleAWSException("Unable to subscribe topic " + topic + " with sub ARN "
                    + topic.getSubscriptionArn(), e);
        }
    }

    @Override
    protected NevadoMessage convertSqsMessage(NevadoDestination destination, SQSMessage sqsMessage, boolean readOnly) throws JMSException {
        NevadoMessage message = super.convertSqsMessage(destination, sqsMessage, false);
        if (sqsMessage.getAttributes() != null) {
            Integer count = null;
            String countAttr = sqsMessage.getAttributes().get(MESSAGE_ATTRIBUTE_APPROXIMATE_RECEIVE_COUNT);
            if (countAttr != null) {
                try {
                    count = Integer.parseInt(countAttr);
                } catch (NumberFormatException e) {
                    _log.warn("Unable to parse " + MESSAGE_ATTRIBUTE_APPROXIMATE_RECEIVE_COUNT + ": " + countAttr);
                }
            }

            if (count != null) {
                message.setObjectPropertyIgnoreReadOnly(JMSXProperty.JMSXDeliveryCount + "", count);
            }
        }
        message.setReadOnly(readOnly);
        return message;
    }

    public SqsClient getAmazonSQS() {
        return _amazonSQS;
    }

    public SnsClient getAmazonSNS() {
        return _amazonSNS;
    }

    /** The executor the async send runs on (null unless async) */
    ExecutorService getSQSExecutor() {
        return _sqsExecutor;
    }

    protected String getTopicARN(NevadoTopic topic) throws JMSException {
        if (topic.getArn() == null)
        {
            CreateTopicResponse result;
            try {
                result = _amazonSNS.createTopic(CreateTopicRequest.builder().name(topic.getTopicName()).build());
            }
            catch (SdkException e) {
                throw handleAWSException("Unable to create/lookup topic: " + topic, e);
            }
            topic.setArn(result.topicArn());
        }
        return topic.getArn();
    }

    protected JMSException handleAWSException(String message, SdkException e) {
        JMSException jmsException;
        String exMessage = message + ": " + v1Message(e);
        _log.error(exMessage, e);
        Throwable cause = ioCause(e);
        if (cause != null &&
                (UnknownHostException.class.equals(cause.getClass())
                        || SSLException.class.equals((cause.getClass()))))
        {
            jmsException = new ResourceAllocationException(exMessage);
        }
        else if (isSecurityException(e))
        {
            jmsException = new JMSSecurityException(exMessage);
        }
        else
        {
            jmsException = new JMSException(exMessage);
        }
        return jmsException;
    }

    // The exception's message as AWS SDK v1 wrote it (AmazonServiceException.getMessage(), or AmazonHttpClient's
    // "Unable to execute HTTP request: " for I/O failures), so that the JMSException texts stay the same.
    private String v1Message(SdkException e) {
        if (e instanceof AwsServiceException && ((AwsServiceException) e).awsErrorDetails() != null) {
            AwsServiceException ase = (AwsServiceException) e;
            String serviceName = ase.awsErrorDetails().serviceName();
            return ase.awsErrorDetails().errorMessage()
                    + " (Service: " + ("Sqs".equals(serviceName) ? "AmazonSQS" : "Sns".equals(serviceName) ? "AmazonSNS" : serviceName)
                    + "; Status Code: " + ase.statusCode()
                    + "; Error Code: " + ase.awsErrorDetails().errorCode()
                    + "; Request ID: " + ase.requestId()
                    + "; Proxy: " + _proxyHost + ")";
        }
        Throwable cause = ioCause(e);
        if (cause != null) {
            return "Unable to execute HTTP request: " + cause.getMessage();
        }
        return e.getMessage();
    }

    private static Throwable ioCause(SdkException e) {
        for (Throwable t = e.getCause(); t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof IOException) {
                return t;
            }
        }
        return null;
    }

    private boolean isSecurityException(SdkException e) {
        if (e instanceof AwsServiceException && ((AwsServiceException) e).awsErrorDetails() != null) {
            return AWS_ERROR_CODE_AUTHENTICATION.equals(((AwsServiceException)e).awsErrorDetails().errorCode());
        }
        else {
            return false;
        }
    }
}
