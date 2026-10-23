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

## D9. Ledger posting requires an existing transaction

- **Decision:** `LedgerService.post` and `AccountService.lockForUpdate` use `@Transactional(propagation = MANDATORY)`.
- **Alternatives:** default `REQUIRED`, which silently opens a new transaction when none exists.
- **Why:** a posting outside a use-case transaction would commit lines on their own, and a lock outside one is
  released immediately. Failing fast turns that bug into an exception instead of a silent money or race defect.

## D10. System account ids are not wallets

- **Decision:** `GET /api/v1/wallets/{id}` returns 404 `WALLET_NOT_FOUND` for FEE, CASH_IN and other system ids.
- **Alternatives:** return them like any wallet.
- **Why:** they are internal bookkeeping accounts, not customer resources, and their `balance` column is always 0
  (D7), so showing them would be misleading.

## D11. Wallet creation is not idempotent

- **Decision:** `POST /api/v1/wallets` takes no `Idempotency-Key`.
- **Alternatives:** require the key like the money endpoints.
- **Why:** the brief requires safe retries for deposits, transfers and bill payments. A retried create can only make
  an extra empty wallet; no money moves.

## D12. The ledger repository updates customer balances itself

- **Decision:** `JdbcLedgerRepository` inserts entry and lines, then runs
  `UPDATE accounts SET balance = balance + :delta WHERE id = :id AND type = 'CUSTOMER'` in the same transaction.
- **Alternatives:** have `LedgerService` call `AccountService` to adjust balances.
- **Why:** lines and balance must never diverge, so the code that writes them lives in one place. The
  `type = 'CUSTOMER'` filter is what keeps system accounts derived (D7).

## D13. Lock order comes from PostgreSQL, not from Java

- **Decision:** `lockForUpdate` runs one `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`.
- **Alternatives:** sort ids in Java and lock them one statement at a time.
- **Why:** Java's `UUID.compareTo` compares signed longs and does not match PostgreSQL's uuid ordering. Letting the
  database sort and lock in one statement gives every transaction the same global lock order, so two transfers
  between the same pair of wallets queue instead of deadlocking.

## D14. No `deposits` table

- **Decision:** a deposit is recorded only as its journal entry (`type = DEPOSIT`, `reference_type = DEPOSIT`,
  `reference_id` = deposit id) plus its idempotency row.
- **Alternatives:** a `deposits` table like `transfers`.
- **Why:** a deposit has no lifecycle and no extra data (no fee, no status changes). The entry already says what
  moved, from where, to where and when, and the brief has no endpoint to read deposits back.

## D15. No outbox event for deposits

- **Decision:** deposits write no `outbox_events` row.
- **Alternatives:** emit `DEPOSIT_COMPLETED`.
- **Why:** the notification hook is asked for on transfers (and bill payments). Adding it to deposits later is one
  line in `DepositService`, inside the same transaction.

## D16. Frozen wallets can receive deposits

- **Decision:** `AccountPolicy.canReceive` (customer, any status) also gates deposits.
- **Alternatives:** refuse deposits into frozen wallets.
- **Why:** the brief only says a frozen wallet cannot *send*. Refusing incoming money would be an invented rule.

## D17. Idempotency keys are scoped per operation, and the claim lives in the use-case transaction

- **Decision:** `PRIMARY KEY (scope, key)` with scope DEPOSIT / TRANSFER / BILL_PAYMENT. The key is claimed with
  `INSERT ... ON CONFLICT DO NOTHING` inside the same transaction as the money movement, and the final result
  (success or business rejection) is stored on the row before commit. Same key with a different request
  (SHA-256 fingerprint of the command's fields, e.g. wallet id and amount) returns 422 `IDEMPOTENCY_KEY_REUSED`.
- **Alternatives:** one global key space; a separate "claim" transaction with an `IN_PROGRESS` row that other
  requests poll or get 409 on; catching the unique violation.
- **Why:** claim + money + response commit or roll back together, so there is never a key without its effect or
  an effect without its key. A concurrent duplicate simply waits on the row lock of the first insert and then
  replays its committed response; no polling, no 409. `ON CONFLICT DO NOTHING` is used because a failed INSERT
  would abort the whole PostgreSQL transaction. Scoping keeps a client's deposit key from colliding with its
  transfer key. Unexpected failures and pre-claim errors (validation, 404) store nothing, so a retry is safe.
  Keys are kept forever here; production would expire them after a retention window (e.g. 24h-7d).

## D18. Deposit returns 201 without `Location`; rejections are 422 and replayed

- **Decision:** a completed deposit returns 201 with the deposit id and the balance right after it. Replays return
  the same status and body. A business rejection (deposit into a system account) is stored and replayed as the
  same 422.
- **Alternatives:** 200; a `Location` header.
- **Why:** a deposit creates a new record (201), but the brief has no endpoint to fetch it, so there is nothing to
  point `Location` at.

## D19. Strict JSON numbers via configuration

- **Decision:** `spring.jackson.deserialization.accept-float-as-int: false` and
  `spring.jackson.mapper.allow-coercion-of-scalars: false` in `application.yml`; request amounts are boxed `Long`
  with `@NotNull`; the 1..5,000,000 range is checked only by `Amount`.
- **Alternatives:** a `JacksonConfig` class; accept `BigDecimal` and check the scale by hand.
- **Why:** two properties are less code than a config class. `100.5`, `100.0` and `"100"` become 400
  `MALFORMED_REQUEST` instead of being silently truncated or coerced, and the range lives in one place.

## D20. `transfers` table has no status column

- **Decision:** `transfers(id, from_wallet_id, to_wallet_id, amount, fee, currency, created_at)`. A row exists only
  for a completed transfer. Rejected attempts (insufficient funds, frozen, system account) are recorded only on
  their idempotency key, with the stored 422 response. DB CHECKs mirror the rules: amount 1..5,000,000, fee
  250..10,000, `from <> to`, currency IQD. Both wallet FKs are indexed.
- **Alternatives:** a `status` column (`COMPLETED` / `REJECTED`) and a row per attempt.
- **Why:** a transfer is synchronous and all-or-nothing, so it has no lifecycle. A status column that can only hold
  one value is noise. Storing rejections as rows would add a second record of the same outcome the key already
  stores. The money itself is the TRANSFER journal entry whose `reference_id` is the transfer id.

## D21. `SAME_WALLET` is refused before the idempotency claim

- **Decision:** `TransferCommand`'s constructor throws `SAME_WALLET` (422) when from = to. The claim never runs, so
  nothing is stored. An unknown wallet is found only under the lock, after the claim. It throws 404 and rolls back
  the claim too, the same as deposits.
- **Alternatives:** check same-wallet after the claim and store it as a replayable rejection.
- **Why:** same-wallet can be judged from the request alone, like a bad amount, so it belongs with validation.
  CLAUDE.md 4.6 rule 4 allows exceptions for problems found before the claim. It also avoids locking one row "twice".

## D22. Outbox publisher uses a lease, never sends inside a transaction

- **Decision:** `TransferService` writes a `TRANSFER_COMPLETED` row to `outbox_events` in the transfer's own
  transaction (`OutboxWriter`, MANDATORY propagation). `OutboxPublisher.publishBatch()`:
  1. tx 1: one statement `UPDATE ... SET locked_until = now + lease WHERE id IN (SELECT ... WHERE published_at IS
     NULL AND (locked_until IS NULL OR locked_until < now) ORDER BY created_at LIMIT n FOR UPDATE SKIP LOCKED)
     RETURNING *`, commit;
  2. no transaction: `NotificationSender.send(event)` for each;
  3. tx 2 per event: `published_at = now` (only if still NULL).
  The publisher refuses to run if a transaction is already active.
- **Conflict in CLAUDE.md:** section 10 describes holding `FOR UPDATE SKIP LOCKED` while calling the sender. That
  breaks section 7 and 4.6 rule 5 ("no external call inside a DB transaction. Ever."). The stricter rule wins.
- **Alternatives:** hold the row locks during the send (section 10 as written); a status column `IN_FLIGHT`.
- **Why:** a slow or hanging sender would otherwise keep a transaction and its locks open. `SKIP LOCKED` keeps two
  workers from claiming the same rows at the same moment. The lease keeps them from re-claiming a row whose send
  is still running. Lease expiry recovers rows from a crashed worker.
- **Delivery guarantee:** no event for a rolled-back transfer, and an event for every committed one. Delivery is
  **at-least-once**. If the process dies after `send` but before tx 2, or a send outlives its lease, the event is
  sent again. Consumers deduplicate by the event id (`outbox_events.id`, passed as `OutboxEvent.id()`).
- **Clock:** lease times come from the injected `Clock`, so instances must have roughly synced clocks. The lease
  (30s) should be much longer than the time to send one batch.
- **Testing limit:** a *failing* sender is not tested. CLAUDE.md 13 allows only the fake biller as a test double. The
  failure path (`send` throws -> row stays unpublished -> retried after lease expiry) shares its recovery path with
  the crash case, which *is* tested (`eventWithExpiredLease_isRecoveredAndPublished`). A compliant way to cover it
  directly would be to widen rule 13 for a scripted `NotificationSender` in `src/test` (like the fake biller), or
  to unit-test `OutboxPublisher` with a lambda sender. Both need the rule relaxed, so neither is done.

## D23. Scheduled jobs gated by `wallet.scheduling.enabled`

- **Decision:** `SchedulingConfig` (`@EnableScheduling`) loads only when `wallet.scheduling.enabled=true`
  (true in `application.yml`). `AbstractIntegrationTest` sets it to false, and tests call `OutboxPublisher` directly.
  Phase 5's reconciler will use the same switch.
- **Alternatives:** a property per job; let the scheduler run in tests and wait with Awaitility.
- **Why:** a background timer publishing rows mid-test makes outbox assertions racy. One switch for all jobs keeps
  tests deterministic and configuration small.

## D24. Transfer returns 201 with the fee and the sender's balance after

- **Decision:** 201 `{transferId, fromWalletId, toWalletId, currency, amount, fee, totalDebited, senderBalance,
  createdAt}`, no `Location` (no GET endpoint, same as D18). Replays return the identical body. Rejections are 422
  with `INSUFFICIENT_FUNDS`, `WALLET_FROZEN` or `SYSTEM_ACCOUNT_NOT_ALLOWED`, checked in that order (system account
  first, then frozen, then funds), stored and replayed. A frozen wallet can still *receive* a transfer (D16).
- **Alternatives:** 200; return the receiver's balance too.
- **Why:** the sender needs to see the fee they paid. `senderBalance` is read under the lock, so it's exact. The
  receiver's balance is not the sender's business.

## D25. Bill payment = hold, external call, settle or refund

- **Decision:** `BillPaymentService.pay` has no transaction. Tx 1 (`BillPaymentSettler.placeHold`) claims the key,
  locks the wallet, checks `BillPaymentPolicy`, posts HOLD (wallet -X, BILLER_CLEARING +X), inserts the row
  `PENDING` and stores the result. Then `BillerClient.pay(paymentId, billerReference, amount)` runs with no
  transaction open; any exception counts as `Unknown`. Tx 2 (`BillPaymentSettler.apply`) posts SETTLE
  (CLEARING -X, BILLER_SETTLED +X) for Approved, REFUND (CLEARING -X, wallet +X, `reverses_entry_id` = hold) for
  Declined, or marks `UNKNOWN` and leaves the money in clearing. The settler is a separate bean so both steps go
  through the transaction proxy. Bill payments carry no fee.
- **Alternatives:** one transaction around the biller call; debit the wallet only after the biller approves.
- **Why:** a transaction around outside I/O holds the wallet lock for as long as the biller takes (CLAUDE.md 4.6
  rule 5). Debiting after approval could pay a bill the wallet can no longer cover. Holding first means the money
  is always in exactly one place: the wallet, clearing, or settled. The test invariant "clearing balance = sum of
  open payments" checks this after every integration test.

## D26. `apply` updates status first, then moves money

- **Decision:** `apply` starts with `UPDATE bill_payments SET status = ? WHERE id = ? AND status IN (allowed
  sources) RETURNING *`. The allowed sources come from `BillPaymentStatus.canTransitionTo`. Only the caller that
  gets a row back posts SETTLE or REFUND and writes the outbox event; a caller that gets nothing changes nothing.
  If a late answer contradicts the stored final status (e.g. Approved for a FAILED row), it logs
  `event=bill_payment_outcome_conflict` at ERROR and changes nothing.
- **Deviation from CLAUDE.md 4.6 step order** (post, then update the use-case row): here the conditional update is
  the race guard, so it must come first. `UNIQUE (reference_type, reference_id, type)` on `journal_entries` is the
  backstop if it were ever bypassed.
- **Lock order:** the refund locks the payment row (by the UPDATE), then the wallet. Nothing locks them the other
  way: the hold locks a wallet but only inserts a new payment row. So no deadlock.

## D27. Bill payment HTTP statuses and retries

- **Decision:** 201 when the outcome is final, `SUCCEEDED` or `FAILED` (declined, wallet refunded), with the status
  in the body. 202 while `PENDING` or `UNKNOWN`. 422 for rejections before the hold (`INSUFFICIENT_FUNDS`,
  `WALLET_FROZEN`, `SYSTEM_ACCOUNT_NOT_ALLOWED`), stored and replayed like transfers. The key stores a
  `Processed(paymentId, ...)` result, and a replay reloads the row, so retrying with the same `Idempotency-Key`
  returns the payment's *current* status. A replay never calls the biller.
- **Alternatives:** 422 `BILLER_DECLINED` for a decline; replay the exact first response (always PENDING).
- **Why:** the brief fixes the endpoints, so there is no GET for bill payments. A retry is the only way for a
  client to learn how an UNKNOWN payment ended. A decline is a valid, finished payment record, not a request
  error.

## D28. Request carries `billerReference`; the biller deduplicates by our payment id

- **Decision:** `{walletId, billerReference, amount}`. `billerReference` (1..64 chars, not blank) is the
  customer's bill number at the biller. The biller's retry key is our `bill_payments.id`.
- **Why:** a payment needs to say which bill it pays. Our own id is unique per payment and known before the call,
  so a repeated `pay()` for it can never pay twice.

## D29. Bill payment outcomes go through the outbox

- **Decision:** `BILL_PAYMENT_SUCCEEDED` / `BILL_PAYMENT_FAILED` events are written in the same transaction as the
  settle or refund. There is no event for the hold or for UNKNOWN.
- **Why:** CLAUDE.md section 10 asks for it ("or a bill payment"), and it costs one line per outcome. The customer
  is told only about final outcomes that committed.

## D30. Reconciler: leased claims, trusts `status()`, never re-pays

- **Decision:** `BillPaymentReconciler.reconcileBatch()` (on a timer via `ReconcilerScheduler`, gated by D23):
  1. tx: one `UPDATE ... SET locked_until = now + lease, attempts = attempts + 1 WHERE id IN (SELECT ... WHERE
     status IN ('PENDING','UNKNOWN') AND locked_until <= now ORDER BY locked_until LIMIT n FOR UPDATE SKIP LOCKED)
     RETURNING *`, then commit;
  2. no tx: `biller.status(id)`. PAID becomes Approved, NOT_PAID becomes Declined, an exception becomes Unknown
     (checked again after `recheck-interval`);
  3. `BillPaymentSettler.apply`, the same code as the live path.
  The reconciler never calls `pay()`.
- **Alternatives:** retry `pay()` for NOT_PAID rows; pick up PENDING rows by age (`created_at < now - threshold`).
- **Why:** `status()` is the brief's source of truth. Refunding a payment the biller has no record of is the
  simplest correct ending, and a reconciler that only reads cannot pay anything twice. The lease covers both the
  "PENDING older than a threshold" rule and multi-instance safety: a row becomes due only when its owner's lease has
  run out, and `SKIP LOCKED` plus the new lease stop two reconcilers from taking the same row.
- **The one assumption:** NOT_PAID is final only if no `pay()` of ours can still reach the biller. That holds when the
  biller HTTP client's timeout is well below `wallet.billpayment.lease` (30s). If a real biller could accept a
  request after our timeout, it would need a void/cancel call, or the reconciler would have to re-`pay()`
  (idempotent by our reference) instead of refunding. This is noted in the config and on `BillerClient`.
- **Unbounded retries:** a row whose status check keeps failing stays UNKNOWN and is checked every minute, forever.
  `attempts` is there so an alert can fire after N attempts. The alert itself is not built.

## D31. One `locked_until` column instead of `locked_until` + `next_check_at`

- **Decision:** `bill_payments.locked_until` means "nobody touches this open row before this time". It is set to
  now + lease at the hold and at a reconciler claim, to now + recheck-interval after an UNKNOWN answer, and to NULL
  once the row is final. `CHECK ((status open) = (locked_until IS NOT NULL))`.
- **Conflict in CLAUDE.md:** section 9 sketches both columns.
- **Why:** the two columns would mean the same thing ("not due before X") and could disagree. One column gives one
  partial index (`WHERE status IN ('PENDING','UNKNOWN')`) and one condition in the claim query.
