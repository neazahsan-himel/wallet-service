-- Transactional outbox. A use case inserts its event in the same transaction as the money movement, so an event
-- exists exactly when the movement committed. A publisher delivers rows later, outside any transaction.
CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,
    event_type   VARCHAR(40)  NOT NULL,
    aggregate_id UUID         NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    -- Lease: while in the future, one publisher owns the row and others skip it. Expiry hands it back,
    -- which is how a crashed publisher's rows get delivered.
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,

    CONSTRAINT outbox_events_type_chk
        CHECK (event_type IN ('TRANSFER_COMPLETED'))
);

-- The publisher only ever looks at unpublished rows, oldest first; published rows drop out of the index.
CREATE INDEX outbox_events_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;
