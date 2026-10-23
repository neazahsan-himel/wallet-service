package com.neaz.wallet.idempotency.application;

import com.neaz.wallet.idempotency.application.port.IdempotencyRepository;
import com.neaz.wallet.idempotency.domain.IdempotencyKey;
import com.neaz.wallet.idempotency.domain.IdempotencyRecord;
import com.neaz.wallet.idempotency.domain.IdempotencyStatus;
import com.neaz.wallet.idempotency.domain.RequestFingerprint;
import com.neaz.wallet.idempotency.domain.Scope;
import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * Makes retries safe. A use case claims its key first, inside its own transaction, and stores its result on
 * the key before committing. Because claim, money movement and stored result commit or roll back together,
 * a key row exists exactly when the operation happened.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository keys;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository keys, JsonMapper json, Clock clock) {
        this.keys = keys;
        this.json = json;
        this.clock = clock;
    }

    /**
     * MANDATORY: a claim outside the use-case transaction would commit on its own and survive a rollback of the
     * money movement, leaving a key with nothing behind it.
     *
     * <p>The insert uses ON CONFLICT DO NOTHING rather than catching a unique violation, because a failed
     * statement aborts the whole PostgreSQL transaction. If another transaction holds the same key uncommitted,
     * the insert waits for it. If that one commits, we insert nothing and the following SELECT (a new snapshot under
     * READ COMMITTED) sees its stored result. If it rolls back, our insert succeeds and we do the work.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <R> Claim<R> claim(Scope scope, IdempotencyKey key, RequestFingerprint fingerprint, Class<R> resultType) {
        if (keys.insertIfAbsent(scope, key, fingerprint, clock.instant())) {
            return new Claim.New<>(scope, key);
        }

        IdempotencyRecord existing = keys.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("key conflicted but row not found: " + scope + " " + key.value()));
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different request");
        }
        if (existing.status() != IdempotencyStatus.COMPLETED) {
            // Unreachable while claim and complete share one transaction; fail loudly if that ever breaks.
            throw new IllegalStateException("committed key is not completed: " + scope + " " + key.value());
        }
        log.info("event=idempotent_replay scope={} idempotencyKey={}", scope, key.value());
        return new Claim.Replay<>(json.readValue(existing.responseBody(), resultType));
    }

    /** Stores the final result so retries replay it. {@code resourceId} is null for rejections. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <R> void complete(Claim.New<R> claim, R result, UUID resourceId) {
        boolean updated = keys.markCompleted(claim.scope(), claim.key(), json.writeValueAsString(result), resourceId);
        if (!updated) {
            throw new IllegalStateException("no in-progress key to complete: " + claim.scope() + " " + claim.key().value());
        }
    }
}
