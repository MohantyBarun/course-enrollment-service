package com.student.studentservice.messaging;

import com.student.studentservice.notification.NotificationDispatcher;
import com.student.studentservice.outbox.OutboxEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Consumes notification events. Failures are retried with exponential backoff
 * (spring.rabbitmq.listener.simple.retry.*), then dead-lettered to notifications.email.dlq.
 */
@Component
@ConditionalOnProperty(name = "app.messaging.rabbit.enabled", havingValue = "true")
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationDispatcher dispatcher;

    @RabbitListener(queues = RabbitMessagingConfig.EMAIL_QUEUE)
    public void onMessage(Message message) {
        MessageProperties properties = message.getMessageProperties();
        UUID id;
        try {
            id = UUID.fromString(properties.getMessageId());
        } catch (IllegalArgumentException | NullPointerException e) {
            // Retrying can't fix a message without a usable id: dead-letter it straight away
            throw new AmqpRejectAndDontRequeueException("Message without a valid messageId", e);
        }
        dispatcher.dispatch(new OutboxEvents.Message(id, properties.getType(),
                new String(message.getBody(), StandardCharsets.UTF_8)));
    }
}
