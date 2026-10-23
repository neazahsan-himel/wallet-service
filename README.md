# Wallet service (IQD)

A REST service for Iraqi dinar wallets: create, deposit, transfer, read balance, and pay a biller. It stays
correct under concurrent load and retries, and never loses or doubles money with an unreliable biller.
Java 21, Spring Boot 4.1, PostgreSQL 16, Flyway, plain JDBC (`JdbcClient`), Testcontainers.

## Run

```bash
./mvnw verify                                  # all tests; needs only Docker + JDK 21 (Testcontainers starts PostgreSQL)
docker compose up -d && ./mvnw spring-boot:run # the service on http://localhost:8088
```

On Windows use `mvnw.cmd`. Money endpoints require an `Idempotency-Key` header. Step-by-step guide and
Postman collection: [docs/TESTING.md](docs/TESTING.md). Full decision log: [docs/DECISIONS.md](docs/DECISIONS.md).

## Calls I made where the brief was silent

| Topic | Decision and why |
|---|---|
| Money type | `long` whole dinars, `BIGINT`. IQD has no fractions, so integers are exact. Jackson rejects `100.5`, `100.0` and `"100"` instead of truncating. |
| Fee math | `clamp((amount + 100) / 200, 250, 10_000)`: integer HALF_UP, no floating point. |
| Amount limit | 1..5,000,000 also applies to deposits and bill payments: one rule everywhere. |
| IDs | UUIDv7: time-ordered for index locality, still unguessable. |
| Frozen wallet | Can't send or pay bills, can still receive. The brief only forbids sending. |
| System accounts | Any customer operation touching FEE/CASH_IN returns 422 `SYSTEM_ACCOUNT_NOT_ALLOWED`; `GET` on them is 404. |
| Status codes | 201 created, 202 while a bill payment is `PENDING`/`UNKNOWN`, 400 validation, 404 unknown wallet, 422 business rules. Errors are RFC 9457 ProblemDetail with a stable `code`. |
| Bill payment status | No GET endpoint. A retry with the same key returns the *current* status and never calls the biller again. A decline is 201 `FAILED` (refunded), not an error. |
| Request shapes | Bill payment `{walletId, billerReference, amount}`, no fee. Wallet creation `{ownerId}`, no idempotency key: a duplicate only creates an empty wallet. |
| Same wallet / deposits | from = to is a validation error before the key is claimed. Deposits have no own table or notification; the journal entry records them. |
| Biller says NOT_PAID | The reconciler refunds; `status()` is the source of truth per the brief. |

## Key design choices and trade-offs

- **Double-entry ledger.** Every movement is one journal entry whose lines sum to zero; triggers block
  `UPDATE`/`DELETE` and reject unbalanced entries at commit. Corrections are reversing entries. Every
  integration test ends by checking `SUM(lines) = 0` and each wallet balance = sum of its lines.
- **One transaction per use case, fixed order:** claim idempotency key → lock wallets → check rules →
  post ledger → write transfer/payment row → write outbox event → store response. All or nothing.
- **Locking.** One `SELECT … ORDER BY id FOR UPDATE`, so PostgreSQL sets the lock order (Java's `UUID`
  ordering differs) and opposite transfers queue instead of deadlocking. Checks run only after the lock;
  `CHECK (balance >= 0)` is the last line of defence.
- **Idempotency in the same transaction** via `INSERT … ON CONFLICT DO NOTHING`, so a duplicate never aborts
  the transaction; it waits and replays the stored response. Business rejections are replayed too;
  unexpected errors roll back the claim. Same key + different body → 422 `IDEMPOTENCY_KEY_REUSED`.
- **Bill payments: hold → call → settle.** Tx 1 moves money to BILLER_CLEARING. The biller is called with no
  transaction open, using our payment id as its idempotency key. Tx 2 settles, refunds, or marks `UNKNOWN`.
  A conditional `UPDATE … WHERE status IN (…)` picks one winner and a unique key on journal entries makes a
  second settle/refund impossible. A leased `SKIP LOCKED` reconciler only asks `status()`, never `pay()`,
  and reuses the same settle code; this covers restarts and multiple instances.

Trade-offs:

- **No hot row:** only customer balances are stored; system accounts are `SUM(ledger_lines)`. Cost: reading
  a system balance is an aggregate query.
- **Plain JDBC, not JPA:** every lock and conditional update is visible in SQL, at the cost of hand-written mapping.
- **Outbox is at-least-once:** a crash between send and "mark published" re-sends; consumers dedupe by event
  id. Exactly-once would need distributed transactions.
- **Hold before calling the biller:** the customer sees money gone while unknown, but we can never pay a bill
  the wallet couldn't cover.
- **Deliberate departures from my own guide:** the outbox publisher holds no row locks while sending, and the
  bill payment settle step updates status *before* posting money, because that update is the race guard.

**Required tests:** `FeePolicyTest` (rounding edges, clamps); `TransferConcurrencyIT` (50 parallel × 10,000
from 300,000 → exactly 29 succeed, 2,750 left); `TransferRetryIT` (same transfer 10× in parallel → one row,
one entry); `BillPaymentReconciliationIT` (200 payments via a seeded fake biller → all settled, money
reconciles, nothing paid or refunded twice); `ArchitectureTest` (ArchUnit layering). All assert database state.

## Known gaps / with one more day

- **NOT_PAID refund** is safe only if no `pay()` call can reach the biller after the 30s lease. Against a
  biller that accepts late requests I'd add a void call, or re-`pay()` (idempotent) instead of refunding.
- **No real biller client:** `LoggingBillerClient` approves everything; the flaky behaviour is in the test fake.
- **UNKNOWN rows are rechecked forever:** attempts are counted, but nothing alerts after N.
- **Idempotency keys never expire:** production would purge them after 24h–7d.
- **A failing `NotificationSender` isn't tested directly;** its recovery (lease expiry) is shared with the tested crash case.
- **Triggers don't stop `TRUNCATE`** (the production role shouldn't have it); leases need NTP-synced clocks.
- **No metrics:** next would be Micrometer counters for rejections, UNKNOWN payments and outbox lag.

## Time spent

About **14** hours.
