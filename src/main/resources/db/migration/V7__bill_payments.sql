-- Bill payments move money in two steps around an unreliable external call:
-- hold (wallet -> BILLER_CLEARING), then settle (BILLER_CLEARING -> BILLER_SETTLED) or refund (back to the wallet).
-- Money waiting for the biller's answer sits in clearing, so it is never lost and never in two places.
INSERT INTO accounts (id, owner_id, type, created_at) VALUES
    ('00000000-0000-7000-8000-000000000003', NULL, 'BILLER_CLEARING', now()),
    ('00000000-0000-7000-8000-000000000004', NULL, 'BILLER_SETTLED',  now());

CREATE TABLE bill_payments (
    id               UUID          PRIMARY KEY,
    wallet_id        UUID          NOT NULL REFERENCES accounts (id),
    -- The customer's bill number at the biller. Our own id is what the biller deduplicates retries by.
    biller_reference VARCHAR(64)   NOT NULL,
    amount           BIGINT        NOT NULL,
    currency         CHAR(3)       NOT NULL DEFAULT 'IQD',
    status           VARCHAR(10)   NOT NULL,
    -- How many times the reconciler has picked the row up; for logs and alerting.
    attempts         INT           NOT NULL DEFAULT 0,
    -- Nobody touches an open row before this time. Set when the live call or a reconciler owns the row (a lease),
    -- and when an UNKNOWN row is scheduled for its next check. Expiry is how a crashed owner's rows are recovered.
    locked_until     TIMESTAMPTZ,
    -- The HOLD entry, so a refund can point back at the entry it reverses.
    hold_entry_id    UUID          NOT NULL REFERENCES journal_entries (id),
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,

    CONSTRAINT bill_payments_amount_chk
        CHECK (amount BETWEEN 1 AND 5000000),
    CONSTRAINT bill_payments_currency_chk
        CHECK (currency = 'IQD'),
    CONSTRAINT bill_payments_status_chk
        CHECK (status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT bill_payments_reference_chk
        CHECK (length(trim(biller_reference)) > 0),
    -- Open rows always have a time after which they may be picked up; settled rows never do.
    CONSTRAINT bill_payments_lock_chk
        CHECK ((status IN ('PENDING', 'UNKNOWN')) = (locked_until IS NOT NULL))
);

CREATE INDEX bill_payments_wallet_idx ON bill_payments (wallet_id);
CREATE INDEX bill_payments_hold_entry_idx ON bill_payments (hold_entry_id);
-- The reconciler only ever looks at open rows, earliest due first; settled rows drop out of the index.
CREATE INDEX bill_payments_open_idx ON bill_payments (locked_until) WHERE status IN ('PENDING', 'UNKNOWN');

ALTER TABLE outbox_events DROP CONSTRAINT outbox_events_type_chk;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_type_chk
    CHECK (event_type IN ('TRANSFER_COMPLETED', 'BILL_PAYMENT_SUCCEEDED', 'BILL_PAYMENT_FAILED'));
