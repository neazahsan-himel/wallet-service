package com.neaz.wallet.billpayment.infrastructure;

import com.neaz.wallet.billpayment.application.port.BillerClient;
import com.neaz.wallet.billpayment.domain.BillerOutcome;
import com.neaz.wallet.billpayment.domain.BillerPaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeholder for a real HTTP client to the biller, so the service runs locally. Approves every payment and
 * remembers the references it approved, so {@link #status} tells the truth within this process.
 * A real client would send the request with a timeout well below {@code wallet.billpayment.lease}.
 * Tests replace this bean with a deterministic flaky fake.
 */
@Component
public class LoggingBillerClient implements BillerClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingBillerClient.class);

    private final Set<UUID> paid = ConcurrentHashMap.newKeySet();

    @Override
    public BillerOutcome pay(UUID reference, String billerReference, long amount) {
        paid.add(reference);
        log.info("event=biller_pay reference={} amount={}", reference, amount);
        return new BillerOutcome.Approved();
    }

    @Override
    public BillerPaymentStatus status(UUID reference) {
        return paid.contains(reference) ? BillerPaymentStatus.PAID : BillerPaymentStatus.NOT_PAID;
    }
}
