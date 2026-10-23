package com.neaz.wallet.billpayment.infrastructure;

import com.neaz.wallet.billpayment.application.port.BillPaymentRepository;
import com.neaz.wallet.billpayment.domain.BillPayment;
import com.neaz.wallet.billpayment.domain.BillPaymentStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcBillPaymentRepository implements BillPaymentRepository {

    private static final RowMapper<BillPayment> ROW_MAPPER = (rs, rowNum) -> new BillPayment(
            rs.getObject("id", UUID.class),
            rs.getObject("wallet_id", UUID.class),
            rs.getString("biller_reference"),
            rs.getLong("amount"),
            BillPaymentStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            toInstant(rs.getObject("locked_until", OffsetDateTime.class)),
            rs.getObject("hold_entry_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public JdbcBillPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(BillPayment payment) {
        jdbc.sql("""
                        INSERT INTO bill_payments (id, wallet_id, biller_reference, amount, status, attempts,
                                                   locked_until, hold_entry_id, created_at, updated_at)
                        VALUES (:id, :walletId, :billerReference, :amount, :status, :attempts,
                                :lockedUntil, :holdEntryId, :createdAt, :updatedAt)
                        """)
                .param("id", payment.id())
                .param("walletId", payment.walletId())
                .param("billerReference", payment.billerReference())
                .param("amount", payment.amount())
                .param("status", payment.status().name())
                .param("attempts", payment.attempts())
                .param("lockedUntil", utc(payment.lockedUntil()))
                .param("holdEntryId", payment.holdEntryId())
                .param("createdAt", utc(payment.createdAt()))
                .param("updatedAt", utc(payment.updatedAt()))
                .update();
    }

    @Override
    public Optional<BillPayment> findById(UUID id) {
        return jdbc.sql("SELECT * FROM bill_payments WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * The WHERE clause is the guard: the UPDATE row-locks the payment, and if a concurrent caller committed a
     * transition first, PostgreSQL re-checks the condition on the new row version and updates nothing.
     */
    @Override
    public Optional<BillPayment> transition(UUID id, BillPaymentStatus to, Instant lockedUntil, Instant now) {
        var from = BillPaymentStatus.allowedSourcesOf(to).stream().map(Enum::name).toList();
        return jdbc.sql("""
                        UPDATE bill_payments
                        SET status = :to, locked_until = :lockedUntil, updated_at = :now
                        WHERE id = :id AND status IN (:from)
                        RETURNING *
                        """)
                .param("to", to.name())
                .param("lockedUntil", utc(lockedUntil))
                .param("now", utc(now))
                .param("id", id)
                .param("from", from)
                .query(ROW_MAPPER)
                .optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
