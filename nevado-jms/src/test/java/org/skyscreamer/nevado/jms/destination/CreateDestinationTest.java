package org.skyscreamer.nevado.jms.destination;

import junit.framework.Assert;
import org.junit.Test;
import org.skyscreamer.nevado.jms.AbstractJMSTest;

import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.Topic;

/**
 * Test creation of destinations (JMS 1.1, sec 4.4.4)
 *
 * @author Carter Page <carter@skyscreamer.org>
 */
public class CreateDestinationTest extends AbstractJMSTest {
    @Test
    public void testCreateQueue() throws JMSException {
        Session session = createSession();
        Queue queue = session.createQueue("aQueue");
        Assert.assertNotNull(queue);
        Assert.assertTrue(queue instanceof NevadoQueue);
        Assert.assertEquals("aQueue", queue.getQueueName());
    }

    @Test
    public void testCreateTopic() throws JMSException {
        Session session = createSession();
        Topic topic = session.createTopic("aTopic");
        Assert.assertNotNull(topic);
        Assert.assertTrue(topic instanceof NevadoTopic);
        Assert.assertEquals("aTopic", topic.getTopicName());
    }
}
