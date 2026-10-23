package com.neaz.wallet.billpayment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class BillPaymentStatusTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
            "PENDING,   UNKNOWN,   true",
            "PENDING,   SUCCEEDED, true",
            "PENDING,   FAILED,    true",
            "PENDING,   PENDING,   false",
            "UNKNOWN,   UNKNOWN,   true",
            "UNKNOWN,   SUCCEEDED, true",
            "UNKNOWN,   FAILED,    true",
            "UNKNOWN,   PENDING,   false",
            "SUCCEEDED, FAILED,    false",
            "SUCCEEDED, UNKNOWN,   false",
            "SUCCEEDED, SUCCEEDED, false",
            "FAILED,    SUCCEEDED, false",
            "FAILED,    UNKNOWN,   false",
            "FAILED,    FAILED,    false",
    })
    void transitions(BillPaymentStatus from, BillPaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(value = BillPaymentStatus.class, names = {"SUCCEEDED", "FAILED", "UNKNOWN"})
    void onlyOpenStatusesMayMoveOn(BillPaymentStatus next) {
        assertThat(BillPaymentStatus.allowedSourcesOf(next))
                .containsExactlyInAnyOrder(BillPaymentStatus.PENDING, BillPaymentStatus.UNKNOWN);
    }

    @Test
    void nothingMovesBackToPending() {
        assertThat(BillPaymentStatus.allowedSourcesOf(BillPaymentStatus.PENDING)).isEmpty();
    }

    @Test
    void finalStatusesAreNotOpen() {
        assertThat(BillPaymentStatus.PENDING.isOpen()).isTrue();
        assertThat(BillPaymentStatus.UNKNOWN.isOpen()).isTrue();
        assertThat(BillPaymentStatus.SUCCEEDED.isOpen()).isFalse();
        assertThat(BillPaymentStatus.FAILED.isOpen()).isFalse();
    }

    @Test
    void billerAnswersMapToFinalOrUnknownStatus() {
        assertThat(new BillerOutcome.Approved().resultingStatus()).isEqualTo(BillPaymentStatus.SUCCEEDED);
        assertThat(new BillerOutcome.Declined("no").resultingStatus()).isEqualTo(BillPaymentStatus.FAILED);
        assertThat(new BillerOutcome.Unknown("timeout").resultingStatus()).isEqualTo(BillPaymentStatus.UNKNOWN);
        assertThat(BillerPaymentStatus.PAID.toOutcome()).isInstanceOf(BillerOutcome.Approved.class);
        assertThat(BillerPaymentStatus.NOT_PAID.toOutcome()).isInstanceOf(BillerOutcome.Declined.class);
    }
}
