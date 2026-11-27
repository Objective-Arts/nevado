package org.skyscreamer.nevado.jms.connector.amazonaws;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import org.skyscreamer.nevado.jms.connector.SQSQueue;
import org.skyscreamer.nevado.jms.util.MessageIdUtil;

import jakarta.jms.JMSException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Representation of an AmazonAWS queue
 *
 * @author Carter Page <carter@skyscreamer.org>
 */
public class AmazonAwsSQSQueue implements SQSQueue {
    public static final String ATTRIBUTE_QUEUE_ARN = "QueueArn";
    public static final String ATTRIBUTE_POLICY = "Policy";
    private final String _queueUrl;
    private final AmazonAwsSQSConnector _amazonAwsSQSConnector;
    private final boolean _isAsync;

    public AmazonAwsSQSQueue(AmazonAwsSQSConnector amazonAwsSQSConnector, String queueUrl) {
        this(amazonAwsSQSConnector, queueUrl, false);
    }

    public AmazonAwsSQSQueue(AmazonAwsSQSConnector amazonAwsSQSConnector, String queueUrl, boolean isAsync) {
        _amazonAwsSQSConnector = amazonAwsSQSConnector;
        _queueUrl = queueUrl;
        _isAsync = isAsync;
    }

    @Override
    public String sendMessage(String serializedMessage) throws JMSException {
        String messageId;
        final SendMessageRequest request = SendMessageRequest.builder().queueUrl(_queueUrl).messageBody(serializedMessage).build();
        try {
            if (_isAsync) {
                _amazonAwsSQSConnector.getSQSExecutor().submit(() -> _amazonAwsSQSConnector.getAmazonSQS().sendMessage(request));
                messageId = null;
            } else {
                messageId = _amazonAwsSQSConnector.getAmazonSQS().sendMessage(request).messageId();
            }
        }
        catch (SdkException e) {
            throw _amazonAwsSQSConnector.handleAWSException("Unable to send message to queue " + _queueUrl, e);
        }
        return messageId;
    }

    @Override
    public void setMessageVisibilityTimeout(String sqsReceiptHandle, int timeout) throws JMSException {
        try {
            _amazonAwsSQSConnector.getAmazonSQS().changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(_queueUrl).receiptHandle(sqsReceiptHandle).visibilityTimeout(timeout).build());
        }
        catch (SdkException e) {
            throw _amazonAwsSQSConnector.handleAWSException("Unable to reset message visibility for message "
                    + "with receipt handle " + sqsReceiptHandle, e);
        }
    }

    @Override
    public String getQueueARN() throws JMSException {
        GetQueueAttributesRequest request = GetQueueAttributesRequest.builder().queueUrl(_queueUrl).attributeNamesWithStrings(ATTRIBUTE_QUEUE_ARN).build();
        Map<String, String> queueAttributes;
        try {
            queueAttributes = _amazonAwsSQSConnector.getAmazonSQS().getQueueAttributes(request).attributesAsStrings();
        }
        catch (SdkException e) {
            throw _amazonAwsSQSConnector.handleAWSException("Unable to get queue ARN for queue " + _queueUrl, e);
        }
        return queueAttributes.get(ATTRIBUTE_QUEUE_ARN);
    }

    @Override
    public void setPolicy(String policy) throws JMSException {
        try {
            _amazonAwsSQSConnector.getAmazonSQS().setQueueAttributes(SetQueueAttributesRequest.builder().queueUrl(_queueUrl)
                    .attributesWithStrings(Collections.singletonMap(ATTRIBUTE_POLICY, policy)).build());
        } catch (SdkException e) {
            throw _amazonAwsSQSConnector.handleAWSException("Unable to set policy", e);
        }
    }

    @Override
    public void deleteMessage(String sqsReceiptHandle) throws JMSException {
        try {
            _amazonAwsSQSConnector.getAmazonSQS().deleteMessage(DeleteMessageRequest.builder().queueUrl(_queueUrl).receiptHandle(sqsReceiptHandle).build());
        } catch (SdkException e) {
            throw _amazonAwsSQSConnector.handleAWSException("Unable to delete message with receipt handle " + sqsReceiptHandle, e);
        }
    }

    @Override
    public AmazonAwsSQSMessage receiveMessage() throws JMSException {
        AmazonAwsSQSMessage sqsMessage;
        try {
            ReceiveMessageRequest request = ReceiveMessageRequest.builder().queueUrl(_queueUrl)
                    .attributeNamesWithStrings(AmazonAwsSQSConnector.MESSAGE_ATTRIBUTE_APPROXIMATE_RECEIVE_COUNT).build();
            ReceiveMessageResponse result = _amazonAwsSQSConnector.getAmazonSQS().receiveMessage(request);
            List<Message> messages = result.messages();
            sqsMessage = (messages != null && messages.size() > 0) ? new AmazonAwsSQSMessage(messages.get(0)) : null;
        } catch (SdkException e) {
            throw _amazonAwsSQSConnector.handleAWSException("Unable to retrieve message from queue " + _queueUrl, e);
        }
        return sqsMessage;
    }

    @Override
    public void deleteQueue() throws JMSException {
        try {
            _amazonAwsSQSConnector.getAmazonSQS().deleteQueue(DeleteQueueRequest.builder().queueUrl(_queueUrl).build());
        } catch (SdkException e) {
            throw _amazonAwsSQSConnector.handleAWSException("Unable to delete message queue '" + _queueUrl, e);
        }
    }

}
