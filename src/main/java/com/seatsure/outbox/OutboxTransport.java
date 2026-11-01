package com.seatsure.outbox;

/**
 * Delivers an outbox event. Returning normally means delivery is guaranteed (e.g. the broker
 * confirmed it); throwing makes the relay retry later with backoff.
 */
public interface OutboxTransport {

    void send(OutboxEvents.Message message) throws Exception;
}
