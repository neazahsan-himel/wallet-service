package com.neaz.wallet.idempotency.infrastructure;

import com.neaz.wallet.idempotency.application.port.IdempotencyRepository;
import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.idempotency.domain.IdempotencyRecord;
import com.neaz.wallet.idempotency.domain.IdempotencyStatus;
import com.neaz.wallet.idempotency.domain.RequestFingerprint;
import com.neaz.wallet.idempotency.domain.Scope;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            Scope.valueOf(rs.getString("scope")),
            IdempotencyKey.of(rs.getString("key")),
            new RequestFingerprint(rs.getString("request_hash")),
            IdempotencyStatus.valueOf(rs.getString("status")),
            rs.getString("response_body"),
            rs.getObject("resource_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public JdbcIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(Scope scope, IdempotencyKey key, RequestFingerprint fingerprint, Instant createdAt) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (scope, key, request_hash, status, created_at)
                        VALUES (:scope, :key, :hash, 'IN_PROGRESS', :createdAt)
                        ON CONFLICT (scope, key) DO NOTHING
                        """)
                .param("scope", scope.name())
                .param("key", key.value())
                .param("hash", fingerprint.value())
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(Scope scope, IdempotencyKey key) {
        return jdbc.sql("SELECT * FROM idempotency_keys WHERE scope = :scope AND key = :key")
                .param("scope", scope.name())
                .param("key", key.value())
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public boolean markCompleted(Scope scope, IdempotencyKey key, String responseBody, UUID resourceId) {
        int updated = jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_body = CAST(:body AS JSONB), resource_id = :resourceId
                        WHERE scope = :scope AND key = :key AND status = 'IN_PROGRESS'
                        """)
                .param("body", responseBody)
                .param("resourceId", resourceId)
                .param("scope", scope.name())
                .param("key", key.value())
                .update();
        return updated == 1;
    }
}
