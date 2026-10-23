-- One row per client retry key. The key is claimed in the same transaction as the money movement it protects,
-- so a committed row means the operation committed, and a rolled-back operation leaves no row behind.
CREATE TABLE idempotency_keys (
    scope         VARCHAR(20)   NOT NULL,
    key           VARCHAR(100)  NOT NULL,
    -- SHA-256 (hex) of the request's fields; same key with a different request is refused.
    request_hash  CHAR(64)      NOT NULL,
    status        VARCHAR(12)   NOT NULL,
    response_body JSONB,
    resource_id   UUID,
    created_at    TIMESTAMPTZ   NOT NULL,

    PRIMARY KEY (scope, key),

    CONSTRAINT idempotency_keys_scope_chk
        CHECK (scope IN ('DEPOSIT', 'TRANSFER', 'BILL_PAYMENT')),
    CONSTRAINT idempotency_keys_status_chk
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- A completed key always has a response to replay.
    CONSTRAINT idempotency_keys_response_chk
        CHECK ((status = 'COMPLETED') = (response_body IS NOT NULL))
);
