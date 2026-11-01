package com.seatsure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsure.entity.OutboxEvent;
import com.seatsure.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Records domain events in the outbox as part of the caller's transaction. */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEvents;

    /**
     * MANDATORY: an outbox write outside a business transaction would defeat the pattern
     * (the event and the change must commit or roll back together), so that's a programming error.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, Object aggregateId, String eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + eventType + " event", e);
        }
        OutboxEvent event = outboxRepository.save(
                OutboxEvent.pending(aggregateType, String.valueOf(aggregateId), eventType, json, LocalDateTime.now()));
        // Picked up by OutboxRelay after commit, so delivery doesn't wait for the next poll
        applicationEvents.publishEvent(new OutboxEventRecorded(event.getId()));
    }
}
