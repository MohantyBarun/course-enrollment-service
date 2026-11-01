package com.student.studentservice.messaging;

import com.student.studentservice.outbox.OutboxEvents;
import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology, declared on first connection:
 * <pre>
 * studentservice.events (topic) --student.registered / enrollment.confirmed / notification.test--> notifications.email
 * notifications.email --rejected after retries--> studentservice.events.dlx --> notifications.email.dlq
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "app.messaging.rabbit.enabled", havingValue = "true")
public class RabbitMessagingConfig {

    public static final String EVENTS_EXCHANGE = "studentservice.events";
    public static final String DEAD_LETTER_EXCHANGE = "studentservice.events.dlx";
    public static final String EMAIL_QUEUE = "notifications.email";
    public static final String EMAIL_DEAD_LETTER_QUEUE = "notifications.email.dlq";

    @Bean
    public TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(EMAIL_DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue emailDeadLetterQueue() {
        return QueueBuilder.durable(EMAIL_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Declarables emailBindings() {
        return new Declarables(
                BindingBuilder.bind(emailQueue()).to(eventsExchange()).with(OutboxEvents.STUDENT_REGISTERED),
                BindingBuilder.bind(emailQueue()).to(eventsExchange()).with(OutboxEvents.ENROLLMENT_CONFIRMED),
                BindingBuilder.bind(emailQueue()).to(eventsExchange()).with(OutboxEvents.TEST_EMAIL),
                BindingBuilder.bind(emailDeadLetterQueue()).to(deadLetterExchange()).with(EMAIL_DEAD_LETTER_QUEUE));
    }
}
