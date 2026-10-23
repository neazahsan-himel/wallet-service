package com.neaz.wallet.billpayment.application.port;

import com.neaz.wallet.billpayment.domain.BillerOutcome;
import com.neaz.wallet.billpayment.domain.BillerPaymentStatus;

import java.util.UUID;

/**
 * The external biller (e.g. the electricity company). Always called outside any database transaction.
 *
 * <p>{@code reference} is our bill payment id. The biller never pays the same reference twice: a repeated
 * {@code pay()} for a paid reference returns the original approval. That is what makes our retries safe.
 */
public interface BillerClient {

    /**
     * Asks the biller to pay. May throw on a timeout or transport error, in which case the bill may or may not have
     * been paid; callers treat that as {@link BillerOutcome.Unknown}. Implementations must time out well within the
     * bill payment lease ({@code wallet.billpayment.lease}).
     */
    BillerOutcome pay(UUID reference, String billerReference, long amount);

    /** The biller's record of the reference. The source of truth when a {@code pay()} answer was lost. */
    BillerPaymentStatus status(UUID reference);
}
