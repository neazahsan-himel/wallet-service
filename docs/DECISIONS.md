# Decisions

Calls made where the brief is silent. Each entry: decision, alternatives, why. The README summarises this file.

## D1. PostgreSQL 16

- **Decision:** PostgreSQL 16 (Docker Compose locally, Testcontainers in tests).
- **Alternatives:** MySQL 8.
- **Why:** `SELECT ... FOR UPDATE SKIP LOCKED`, `INSERT ... ON CONFLICT DO NOTHING`, deferrable constraint
  triggers and `JSONB` are all first-class, and they are exactly the tools this service leans on.

## D2. Money is `long` whole dinars

- **Decision:** amounts are `long` in Java and `BIGINT` in the database, wrapped in an `Amount` value object.
- **Alternatives:** `BigDecimal` / `NUMERIC`.
- **Why:** IQD is handled in whole dinars here, so there is no fractional part to represent. Integers make
  equality, summing and overflow checks (`Math.addExact`) exact and cheap.

## D3. The 1..5,000,000 limit applies to every amount

- **Decision:** deposits and bill payments use the same limit as transfers.
- **Alternatives:** limit transfers only and leave deposits unbounded.
- **Why:** the brief says "per transfer" but also "reject anything else". One rule for every amount is simpler
  to explain and test; a separate deposit limit can be added later if the business wants one.

## D4. Fee computed with exact integer arithmetic

- **Decision:** `fee = clamp((amount + 100) / 200, 250, 10_000)`.
- **Alternatives:** `BigDecimal.valueOf(amount).multiply(0.005).setScale(0, HALF_UP)`.
- **Why:** 0.5% is `amount / 200`. For positive integers, adding half the divisor (100) before integer division
  is exactly `HALF_UP`. No floating point, no `BigDecimal` allocation, and `FeePolicyTest` pins the `.5` edges.
  With amount at most 5,000,000 the sum cannot overflow.

## D5. IDs are UUIDv7

- **Decision:** all ids are UUIDv7, generated in the application (`Ids.newId()`).
- **Alternatives:** random UUIDv4, database sequences.
- **Why:** v7 starts with a millisecond timestamp, so new rows land at the end of the B-tree index instead of
  at random pages (better index locality than v4), while ids stay unguessable and can be created before insert.
  Java 21 has no v7 generator, so `Ids` builds one (RFC 9562 layout) in a few lines.

## D6. `owner_id` is an opaque string

- **Decision:** `owner_id VARCHAR(64)`, required for customer wallets, null for system accounts.
- **Alternatives:** `UUID`.
- **Why:** owners live in another system (auth is out of scope) and we don't know their id format.

## D7. System account balances are derived, customer balances are stored

- **Decision:** customer wallets keep a materialised `balance` updated in the same transaction as their ledger
  lines. System accounts (FEE, CASH_IN, biller accounts) keep `balance = 0` in the table; their real balance is
  `SUM(ledger_lines.amount)`.
- **Alternatives:** update the system account balance on every operation.
- **Why:** every transfer credits the single FEE account. Updating its row would make every transfer in the
  system wait on one row lock (a hot row) and serialise all traffic. Customer balances must be stored because
  we lock that row to enforce "never below zero".

## D8. Ledger history is protected in the database

- **Decision:** triggers reject `UPDATE`/`DELETE` on `journal_entries` and `ledger_lines`; a deferred constraint
  trigger rejects any commit where an entry's lines do not sum to zero.
- **Alternatives:** rely on application code only.
- **Why:** "past records can never be changed" and "the books add up" are the two most important promises.
  The Java `Posting` builder enforces them first; the database is the last line of defence against a bug or a
  manual SQL session. `TRUNCATE` is not covered by row triggers; in production the application role would not
  be granted `TRUNCATE`.
