package com.seatsure.outbox;

import com.seatsure.notification.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Used when no broker is configured: the relay hands events straight to the consumer. Delivery is
 * still asynchronous, retried and at-least-once, because it goes through the outbox table.
 */
@Component
@ConditionalOnProperty(name = "app.messaging.rabbit.enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class InProcessOutboxTransport implements OutboxTransport {

    private final NotificationDispatcher dispatcher;

    @Override
    public void send(OutboxEvents.Message message) {
        dispatcher.dispatch(message);
    }
}
