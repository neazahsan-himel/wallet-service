package com.neaz.wallet.outbox.application.port;

import com.neaz.wallet.outbox.domain.OutboxEvent;

/**
 * Delivers an event to the outside world (push, SMS, a message broker). Always called outside any database
 * transaction. May be called more than once for the same event, so receivers deduplicate by {@code event.id()}.
 */
public interface NotificationSender {

    void send(OutboxEvent event);
}
