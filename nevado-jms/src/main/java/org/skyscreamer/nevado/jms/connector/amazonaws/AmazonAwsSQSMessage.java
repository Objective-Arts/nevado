package org.skyscreamer.nevado.jms.connector.amazonaws;

import software.amazon.awssdk.services.sqs.model.Message;
import org.skyscreamer.nevado.jms.connector.SQSMessage;

import java.util.Map;

/**
 * Amazon AWS version of an SQSMessage
 *
 * @author Carter Page <carter@skyscreamer.org>
 */
public class AmazonAwsSQSMessage implements SQSMessage {
    private final Message _message;

    public AmazonAwsSQSMessage(Message message) {
        _message = message;
    }

    @Override
    public String getReceiptHandle() {
        return _message.receiptHandle();
    }

    @Override
    public String getMessageBody() {
        return _message.body();
    }

    @Override
    public String getMessageId() {
        return _message.messageId();
    }

    @Override
    public Map<String, String> getAttributes() {
        return _message.attributesAsStrings();
    }
}
