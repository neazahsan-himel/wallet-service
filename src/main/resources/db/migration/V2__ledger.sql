-- Double-entry ledger. One journal entry per money movement; its lines sum to exactly zero.
CREATE TABLE journal_entries (
    id                UUID         PRIMARY KEY,
    type              VARCHAR(30)  NOT NULL,
    reference_type    VARCHAR(30)  NOT NULL,
    reference_id      UUID         NOT NULL,
    reverses_entry_id UUID         REFERENCES journal_entries (id),
    created_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT journal_entries_type_chk
        CHECK (type IN ('DEPOSIT', 'TRANSFER', 'BILL_PAYMENT_HOLD', 'BILL_PAYMENT_SETTLE', 'BILL_PAYMENT_REFUND')),
    CONSTRAINT journal_entries_reference_type_chk
        CHECK (reference_type IN ('DEPOSIT', 'TRANSFER', 'BILL_PAYMENT')),
    -- Each operation produces each kind of entry at most once, e.g. a bill payment can be refunded only once
    -- even if two instances race to settle it.
    CONSTRAINT journal_entries_reference_uq
        UNIQUE (reference_type, reference_id, type)
);

CREATE INDEX journal_entries_reverses_idx ON journal_entries (reverses_entry_id);

CREATE TABLE ledger_lines (
    id         UUID         PRIMARY KEY,
    entry_id   UUID         NOT NULL REFERENCES journal_entries (id),
    account_id UUID         NOT NULL REFERENCES accounts (id),
    -- Signed: negative = money leaves the account, positive = money arrives.
    amount     BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ledger_lines_amount_chk CHECK (amount <> 0)
);

CREATE INDEX ledger_lines_entry_idx ON ledger_lines (entry_id);
CREATE INDEX ledger_lines_account_idx ON ledger_lines (account_id);

-- History is append-only. Corrections are new reversing entries, never edits.
CREATE FUNCTION reject_ledger_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

CREATE TRIGGER ledger_lines_append_only
    BEFORE UPDATE OR DELETE ON ledger_lines
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

-- Zero-sum check runs at COMMIT (deferred), after all lines of the entry are inserted.
-- The Java Posting builder enforces this first; this catches any code path that bypasses it.
CREATE FUNCTION check_entry_balanced() RETURNS trigger AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT SUM(amount) INTO total FROM ledger_lines WHERE entry_id = NEW.entry_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance: sum is %', NEW.entry_id, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_lines_balanced
    AFTER INSERT ON ledger_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
