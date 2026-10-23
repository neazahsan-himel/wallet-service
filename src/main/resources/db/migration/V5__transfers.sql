-- One row per completed transfer. Transfers are synchronous, so a row exists only when the money moved;
-- rejected attempts live only on their idempotency key. The money itself is in the TRANSFER journal entry
-- whose reference_id is this row's id.
CREATE TABLE transfers (
    id             UUID         PRIMARY KEY,
    from_wallet_id UUID         NOT NULL REFERENCES accounts (id),
    to_wallet_id   UUID         NOT NULL REFERENCES accounts (id),
    amount         BIGINT       NOT NULL,
    fee            BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL DEFAULT 'IQD',
    created_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT transfers_amount_chk
        CHECK (amount BETWEEN 1 AND 5000000),
    CONSTRAINT transfers_fee_chk
        CHECK (fee BETWEEN 250 AND 10000),
    CONSTRAINT transfers_currency_chk
        CHECK (currency = 'IQD'),
    CONSTRAINT transfers_distinct_wallets_chk
        CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX transfers_from_wallet_idx ON transfers (from_wallet_id);
CREATE INDEX transfers_to_wallet_idx ON transfers (to_wallet_id);
