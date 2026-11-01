package com.student.studentservice.messaging;

import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.outbox.OutboxTransport;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Publishes persistent messages and waits for the broker's publisher confirm, so the relay marks an
 * event published only once RabbitMQ has taken responsibility for it. Unroutable messages
 * (mandatory flag, returned by the broker) count as failures too.
 */
@Component
@ConditionalOnProperty(name = "app.messaging.rabbit.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RabbitOutboxTransport implements OutboxTransport {

    private static final long CONFIRM_TIMEOUT_SECONDS = 10;

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void send(OutboxEvents.Message message) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(message.id().toString());
        properties.setType(message.type());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        CorrelationData correlation = new CorrelationData(message.id().toString());
        rabbitTemplate.send(RabbitMessagingConfig.EVENTS_EXCHANGE, message.type(),
                new Message(message.payload().getBytes(StandardCharsets.UTF_8), properties), correlation);

        CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // A return (unroutable) is always delivered before the confirm completes
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Message " + message.id() + " was unroutable: "
                    + correlation.getReturned().getReplyText());
        }
        if (!confirm.isAck()) {
            throw new IllegalStateException("Broker rejected message " + message.id() + ": " + confirm.getReason());
        }
    }
}
