package com.neaz.wallet.outbox.application;

import com.neaz.wallet.outbox.application.port.OutboxRepository;
import com.neaz.wallet.outbox.domain.EventType;
import com.neaz.wallet.outbox.domain.OutboxEvent;
import com.neaz.wallet.shared.kernel.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * Records that something happened, as one more row in the use case's own transaction.
 */
@Service
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outbox, JsonMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    /**
     * MANDATORY: the event must commit or roll back with the money movement it describes. That is what
     * guarantees no notification for a rolled-back transfer and none missing for a committed one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(EventType type, UUID aggregateId, Object payload) {
        var event = new OutboxEvent(Ids.newId(), type, aggregateId, json.writeValueAsString(payload), clock.instant());
        outbox.insert(event);
        return event.id();
    }
}
