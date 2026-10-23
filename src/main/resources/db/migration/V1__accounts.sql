-- Customer wallets and system accounts share one table, so every ledger line points at a real account row.
CREATE TABLE accounts (
    id         UUID         PRIMARY KEY,
    owner_id   VARCHAR(64),
    type       VARCHAR(20)  NOT NULL,
    currency   CHAR(3)      NOT NULL DEFAULT 'IQD',
    balance    BIGINT       NOT NULL DEFAULT 0,
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT accounts_type_chk
        CHECK (type IN ('CUSTOMER', 'FEE', 'CASH_IN', 'BILLER_CLEARING', 'BILLER_SETTLED')),
    CONSTRAINT accounts_currency_chk
        CHECK (currency = 'IQD'),
    CONSTRAINT accounts_status_chk
        CHECK (status IN ('ACTIVE', 'FROZEN')),
    -- Customers must have an owner; system accounts must not.
    CONSTRAINT accounts_owner_chk
        CHECK ((type = 'CUSTOMER') = (owner_id IS NOT NULL)),
    -- Last line of defence: the application checks funds under a row lock, the database refuses anyway.
    CONSTRAINT accounts_customer_balance_chk
        CHECK (type <> 'CUSTOMER' OR balance >= 0),
    -- System account balances are derived from ledger_lines (avoids a hot row on FEE), so the column stays 0.
    CONSTRAINT accounts_system_balance_chk
        CHECK (type = 'CUSTOMER' OR balance = 0)
);
