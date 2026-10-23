package com.neaz.wallet.outbox.infrastructure;

import com.neaz.wallet.outbox.application.port.NotificationSender;
import com.neaz.wallet.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder for a real channel (push, SMS, broker). The brief asks only to show where notifications hook in.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(OutboxEvent event) {
        log.info("event=notification_sent eventId={} type={} aggregateId={}",
                event.id(), event.type(), event.aggregateId());
    }
}
