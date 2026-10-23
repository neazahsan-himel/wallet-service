# CLAUDE.md

Guidance for Claude Code when working in this repository. Read this fully before writing code.
The original brief is in `docs/BRIEF.md`. If this file and the brief disagree, the brief wins; flag it.

## 1. What this project is

A mini wallet service for Iraqi dinar (IQD), built as a Senior Software Engineer assessment.
Reviewers judge: money handling, correctness under concurrent load, failure behaviour,
readable code, tests, and clearly explained trade-offs. They do NOT judge UI, auth, cloud, or extra features.

Golden rule: finished and honest beats big and broken. Do not add features outside the brief.
Every line must be explainable by the author on a call, so prefer plain, obvious code over clever code.

Endpoints (exactly these, nothing more):

| Method | Path                              | Purpose                          |
|--------|-----------------------------------|----------------------------------|
| POST   | /api/v1/wallets                   | Create wallet (balance 0)        |
| POST   | /api/v1/wallets/{id}/deposits     | Cash-in top up                   |
| POST   | /api/v1/transfers                 | Wallet to wallet transfer        |
| GET    | /api/v1/wallets/{id}              | Wallet details and balance       |
| POST   | /api/v1/bill-payments             | Pay a biller (extension)         |

## 2. Commands

```bash
docker compose up -d          # start PostgreSQL for local run
./mvnw spring-boot:run        # run the service
./mvnw verify                 # run ALL tests (unit + integration + architecture; Testcontainers starts its own DB)
```

`./mvnw verify` must work on a clean machine with only Docker and JDK 21 installed.
Always run it before declaring a task done. Never mark a task complete with failing tests.

## 3. Stack (fixed by the brief)

- Java 21, Spring Boot 4.x, Maven (with wrapper committed).
- PostgreSQL 16 (Docker Compose for local, Testcontainers for tests). No H2, no in-memory DB anywhere.
- Flyway for all schema changes. Hibernate must never create or alter schema.
- Testcontainers + JUnit 5 + AssertJ for integration tests. Awaitility for async waits. ArchUnit for architecture rules.

Spring Boot 4 notes (verify exact artifact names on start.spring.io before adding):
- Starters are modularised: e.g. `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`,
  `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-boot-starter-jdbc`.
  Test support has matching `*-test` starters.
- Jackson 3 is the default (package `tools.jackson`). Do not mix in Jackson 2 imports.
- Flyway on PostgreSQL also needs `flyway-database-postgresql`.
- Use `@ServiceConnection` with Testcontainers instead of hand-written datasource properties.
- Data access: `JdbcClient` with explicit SQL so locking and queries are visible.
  Do not hide `SELECT ... FOR UPDATE` behind JPA magic. No JPA in this project.

## 4. Architecture and code structure

Style: modular monolith, package by feature, hexagonal inside each feature.
Pragmatic, not dogmatic: no separate persistence vs domain models, no CQRS, no event sourcing.

### 4.1 Layers inside every feature

| Layer            | Contains                                            | May depend on                                 |
|------------------|-----------------------------------------------------|-----------------------------------------------|
| `api`            | Controllers, request/response records, HTTP mappers | `application`                                 |
| `application`    | Use-case services, commands, results, ports         | `domain`, other features' `application`       |
| `domain`         | Entities, value objects, rules, state machines      | `shared.kernel` only (no Spring, no JDBC)     |
| `infrastructure` | JDBC repositories, external clients, schedulers     | `application`, `domain`                       |

Rules:
- `@Transactional` appears ONLY on `application` service methods. Never on controllers or repositories.
- `domain` is plain Java: no annotations from Spring, Jackson or JDBC. Fully unit testable.
- Repository interfaces (ports) live in `application/port`; JDBC implementations in `infrastructure`.
- A feature talks to another feature only through that feature's public `application` service.
  Never import another feature's `infrastructure` or `api`.
- `ledger` and `account` are core modules; nothing in them may depend on `transfer`, `deposit` or `billpayment`.

### 4.2 Package tree

```
src/main/java/com/neaz/wallet/
  WalletServiceApplication.java

  shared/
    kernel/            Amount (record, 1..5,000,000, IQD), Currency, Ids (UUIDv7)
    error/             BusinessException, ErrorCode (enum with HTTP status + code)
    web/               GlobalExceptionHandler (ProblemDetail), IdempotencyKey header handling
    config/            ClockConfig, JacksonConfig (reject floats), SchedulingConfig

  account/
    api/               WalletController, CreateWalletRequest, WalletResponse
    application/       AccountService (create, get, lockForUpdate(ids...))
      port/            AccountRepository
    domain/            Account, AccountType, AccountStatus, SystemAccounts (seeded ids),
                       AccountPolicy (canSend, canReceive, isCustomer)
    infrastructure/    JdbcAccountRepository

  ledger/
    application/       LedgerService (post(Posting), balanceOf, verifyInvariants)
      port/            LedgerRepository
    domain/            Posting (builder, enforces sum == 0), LedgerLine, JournalEntry,
                       EntryType, ReferenceType
    infrastructure/    JdbcLedgerRepository

  deposit/
    api/               DepositController, DepositRequest, DepositResponse
    application/       DepositService, DepositCommand, DepositResult

  transfer/
    api/               TransferController, TransferRequest, TransferResponse
    application/       TransferService, TransferCommand, TransferResult
      port/            TransferRepository
    domain/            Transfer, FeePolicy (pure: amount -> fee), TransferPolicy
    infrastructure/    JdbcTransferRepository

  billpayment/
    api/               BillPaymentController, BillPaymentRequest, BillPaymentResponse
    application/       BillPaymentService (orchestrator, no tx), BillPaymentSettler (tx steps),
                       BillPaymentReconciler (claim + settle open rows)
      port/            BillerClient, BillPaymentRepository
    domain/            BillPayment, BillPaymentStatus (allowed transitions),
                       BillerOutcome (sealed: Approved | Declined | Unknown)
    infrastructure/    JdbcBillPaymentRepository, ReconcilerScheduler (@Scheduled),
                       LoggingBillerClient (placeholder for a real HTTP client)

  idempotency/
    application/       IdempotencyService (claim, complete), Claim (sealed: New | Replay)
      port/            IdempotencyRepository
    domain/            IdempotencyRecord, RequestFingerprint (SHA-256 of canonical body)
    infrastructure/    JdbcIdempotencyRepository

  outbox/
    application/       OutboxWriter (called inside use-case tx), OutboxPublisher
      port/            OutboxRepository, NotificationSender
    domain/            OutboxEvent, EventType
    infrastructure/    JdbcOutboxRepository, OutboxPollingScheduler, LoggingNotificationSender

src/main/resources/
  application.yml
  db/migration/        V1__accounts.sql, V2__ledger.sql, V3__seed_system_accounts.sql, ...

src/test/java/com/neaz/wallet/
  architecture/        ArchitectureTest (ArchUnit: enforces 4.1 and 4.6 rules)
  support/             AbstractIntegrationTest, LedgerInvariants, ConcurrentRunner,
                       TestDataFactory, FakeBiller (seeded, deterministic)
  <feature>/           unit tests next to domain, *IT for integration tests
```

### 4.3 Key patterns

- **Use case = one service method = one transaction.** Input is a `Command` record, output a `Result`
  record or sealed type. Controllers map HTTP <-> Command/Result, nothing else.
- **Posting builder** is the only way to write money:
  `Posting.of(EntryType.TRANSFER, ReferenceType.TRANSFER, id).debit(sender, total).credit(receiver, amount).credit(FEE, fee).build()`
  `build()` throws if lines do not sum to 0. LedgerService persists entry, lines and customer balance updates.
- **Locking is explicit:** `accountService.lockForUpdate(ids)` sorts ids then runs `SELECT ... FOR UPDATE`.
  Business checks happen only after the lock.
- **External calls only outside transactions** (see 4.6 rule 5).
- **State machines in domain:** `BillPaymentStatus.canTransitionTo(next)`; repository uses conditional
  `UPDATE ... WHERE status IN (...)` and returns whether it won.

### 4.4 Naming

| Kind               | Pattern                         | Example                    |
|--------------------|---------------------------------|----------------------------|
| Use case service   | `<Noun>Service`                 | `TransferService`          |
| Command / result   | `<Noun>Command` / `<Noun>Result`| `TransferCommand`          |
| Port               | role name, no `I` prefix        | `BillerClient`             |
| Adapter            | `<Tech><Port>`                  | `JdbcTransferRepository`   |
| Integration test   | `<Feature>IT`                   | `TransferConcurrencyIT`    |
| Unit test          | `<Class>Test`                   | `FeePolicyTest`            |

Never name a class `*Impl`.

### 4.5 Enforced by tests

`ArchitectureTest` (ArchUnit) must fail the build if:
- `domain` imports `org.springframework..`, `java.sql..`, or `tools.jackson..`
- `api` imports `infrastructure`
- a class outside `application` is annotated `@Transactional`
- a feature imports another feature's `infrastructure` or `api`
- `ledger` or `account` depends on `transfer`, `deposit` or `billpayment`
- any class name ends with `Impl`

### 4.6 Service pattern (application layer)

Every money use case follows the same skeleton. Do not deviate without writing why in DECISIONS.md.

1. **One public method per use case**, takes a `Command` record, returns a `Result` (sealed) record.
2. **`@Transactional` on that method only.** Private helpers never open their own transactions.
3. **Fixed order inside the transaction:**
   1. claim idempotency key (replay and return if already done)
   2. lock accounts (sorted ids, `FOR UPDATE`)
   3. check rules on locked state (via domain policy objects)
   4. post to ledger (Posting builder)
   5. update use-case table (transfer / bill payment row)
   6. write outbox event
   7. store response on the idempotency row
4. **Expected business outcomes are results, not exceptions.** After the key is claimed,
   `INSUFFICIENT_FUNDS` / `WALLET_FROZEN` return `Result.Rejected` so the tx commits and the
   rejection is stored for replay. Exceptions are only for problems before the claim
   (validation, not found) or truly unexpected failures (which roll everything back).
5. **No I/O to the outside world inside a transaction.** If a use case needs an external call,
   split it into an orchestrator (no tx) plus separate transactional steps.
6. **Self-invocation trap:** a `@Transactional` method called from the same class does not go through
   the proxy. Transactional steps used by an orchestrator live in a separate bean
   (`BillPaymentService` -> `BillPaymentSettler`), or use `TransactionTemplate` explicitly.
7. **Services are stateless**, constructor-injected, `final` fields, injected `Clock`.
8. **Idempotency claim never aborts the tx:** use `INSERT ... ON CONFLICT DO NOTHING`. A concurrent
   duplicate waits for the first tx, gets 0 rows, then reads and replays the committed response.
   A plain INSERT that hits a unique violation would poison the PostgreSQL transaction.
9. **No `XService` interface + `XServiceImpl`.** Application services are concrete classes.
   Interfaces exist only at real seams with more than one implementation (now or required by the
   brief): ports such as `BillerClient`, repositories, `NotificationSender`.
   Name adapters by technology (`JdbcTransferRepository`, `HttpBillerClient`).

Reference: synchronous use case

```java
@Service
public class TransferService {

    private final IdempotencyService idempotency;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final TransferRepository transfers;
    private final OutboxWriter outbox;
    private final FeePolicy feePolicy;
    private final Clock clock;

    // constructor omitted

    @Transactional
    public TransferResult transfer(TransferCommand cmd) {
        var claim = idempotency.claim(Scope.TRANSFER, cmd.idempotencyKey(), cmd.fingerprint());
        if (claim instanceof Claim.Replay replay) {
            return replay.as(TransferResult.class);
        }

        var locked = accounts.lockForUpdate(cmd.fromWalletId(), cmd.toWalletId());
        var from = locked.get(cmd.fromWalletId());
        var to = locked.get(cmd.toWalletId());

        long fee = feePolicy.feeFor(cmd.amount());
        long total = Math.addExact(cmd.amount().value(), fee);

        var rejection = TransferPolicy.check(from, to, total);
        if (rejection.isPresent()) {
            var result = new TransferResult.Rejected(rejection.get());
            idempotency.complete(claim, result);
            return result;
        }

        var transferId = Ids.newId();
        ledger.post(Posting.of(EntryType.TRANSFER, ReferenceType.TRANSFER, transferId)
                .debit(from.id(), total)
                .credit(to.id(), cmd.amount().value())
                .credit(SystemAccounts.FEE, fee)
                .build());

        var transfer = Transfer.completed(transferId, from.id(), to.id(), cmd.amount(), fee, clock.instant());
        transfers.insert(transfer);
        outbox.write(OutboxEvent.transferCompleted(transfer));

        var result = new TransferResult.Completed(transfer);
        idempotency.complete(claim, result);
        return result;
    }
}
```

Reference: orchestrated use case with external call

```java
@Service
public class BillPaymentService {               // NO @Transactional here

    private final BillPaymentSettler settler;   // separate bean, holds the tx methods
    private final BillerClient biller;

    public BillPaymentResult pay(BillPaymentCommand cmd) {
        var hold = settler.placeHold(cmd);          // tx 1: idempotency, lock, debit to clearing
        if (!hold.needsBillerCall()) {
            return hold.result();                   // replay or rejection
        }
        BillerOutcome outcome = biller.pay(hold.reference(), hold.amount());  // outside any tx
        return settler.apply(hold.paymentId(), outcome);                       // tx 2: settle / refund / mark UNKNOWN
    }
}
```

`BillPaymentSettler.apply` is also what `BillPaymentReconciler` calls, so the live path and the
recovery path share one piece of settlement code.

Controller stays thin:

```java
@PostMapping("/api/v1/transfers")
ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody TransferRequest body) {
    return TransferHttpMapper.toResponse(service.transfer(body.toCommand(key)));
}
```

## 5. Money rules (non-negotiable)

- Amounts are `long` whole dinars, wrapped in the `Amount` value object at the edges.
  Never `double`/`float`. Never `BigDecimal` stored in DB for IQD.
- DB type: `BIGINT`. Currency column `CHAR(3)` with `CHECK (currency = 'IQD')`.
- Validation: amount > 0 and <= 5,000,000. Reject decimals, strings, nulls, negatives, zero.
  Configure Jackson so `100.5` fails instead of being truncated to `100`.
- Fee: 0.5% of amount, rounded HALF_UP to whole dinars, clamped to [250, 10,000].
  Implement in one pure class `FeePolicy` with no Spring dependencies.
  Use `BigDecimal.valueOf(amount).multiply(new BigDecimal("0.005")).setScale(0, HALF_UP)` or equivalent
  exact integer arithmetic; document which and why. Sender is debited `amount + fee`.
- Bill payments carry no fee.
- Arithmetic overflow: use `Math.addExact` / `Math.subtractExact`.

## 6. Ledger model (double entry)

Every money movement is one `journal_entry` with two or more `ledger_lines` whose signed amounts sum to exactly 0.

Tables (sketch, refine in migrations):
- `accounts(id, owner_id, type, currency, balance, status, created_at)`
  - `type`: `CUSTOMER`, `FEE`, `CASH_IN`, `BILLER_CLEARING`, `BILLER_SETTLED`
  - `status`: `ACTIVE`, `FROZEN`
  - `CHECK (type <> 'CUSTOMER' OR balance >= 0)` as a last line of defence.
  - System accounts are seeded by a Flyway migration with fixed, well-known ids.
- `journal_entries(id, type, reference_type, reference_id, reverses_entry_id NULL, created_at)`
  - `UNIQUE (reference_type, reference_id, type)` so each settlement/refund can exist only once.
- `ledger_lines(id, entry_id, account_id, amount BIGINT <> 0, created_at)`

Rules:
- `journal_entries` and `ledger_lines` are append-only. Add a DB trigger that raises on UPDATE/DELETE.
- Corrections are new reversing entries pointing at the original via `reverses_entry_id`. Never edit history.
- A deposit is: CASH_IN −X, CUSTOMER +X. Cash-in is allowed to go negative (it represents money taken in by agents).
- A transfer is one entry: sender −(amount+fee), receiver +amount, FEE +fee.
- Invariant provable by SQL: `SELECT SUM(amount) FROM ledger_lines` = 0, and each customer
  `accounts.balance` = sum of its ledger lines. The `LedgerInvariants` test helper asserts both,
  and every integration test calls it at the end.
- Balance column update and ledger lines are written in the SAME transaction.

Hot-row note: updating the single FEE account balance on every transfer serialises all transfers.
Default choice: system account balances are derived from `ledger_lines` (no balance column update),
customer balances are materialised. Record this trade-off in the README.

## 7. Concurrency

- One transaction per use case (see 4.6). Isolation READ COMMITTED.
- Lock customer accounts with `SELECT ... FOR UPDATE`, always in ascending id order, to avoid deadlocks.
- Check status, account type and funds AFTER acquiring the lock, never before.
- No external call (biller, notifications) inside a DB transaction. Ever.
- Keep transactions short. No logging of large payloads or sleeps inside them.
- Required test: wallet with 300,000 IQD, 50 parallel transfers of 10,000 (fee 250 each, 10,250 total).
  Exactly 29 succeed, final balance 2,750. Use an `ExecutorService` plus a `CountDownLatch` start gate
  so requests truly overlap, and assert against the DB, not just the responses.

## 8. Idempotency (retries are safe)

- `Idempotency-Key` header is required on deposits, transfers and bill payments. Missing key returns 400.
- Table `idempotency_keys(scope, key, request_hash, status, response_body JSONB, resource_id, created_at)`
  with `PRIMARY KEY (scope, key)`.
- Claim the key with `INSERT ... ON CONFLICT DO NOTHING` in the SAME transaction as the money movement
  (see 4.6 rule 8). Concurrent duplicates wait for the first tx; once it commits they read the row and
  replay the stored response.
- Same key + different request body (fingerprint mismatch) returns 422 `IDEMPOTENCY_KEY_REUSED`.
- Store final business outcomes (success and business rejections like insufficient funds).
  Unexpected failures roll back the claim too, so the client can safely retry.
- Required test: same transfer fired 10 times in parallel. Assert exactly one transfer row,
  one journal entry, correct line count, correct balances on sender, receiver and fee account.

## 9. Bill payments (extension)

State machine on `bill_payments(id, wallet_id, biller_reference, amount, status, attempts, next_check_at, locked_until, ...)`:
`PENDING` -> `SUCCEEDED` | `FAILED`, with `UNKNOWN` while the biller outcome is unclear.

Flow:
1. Tx 1 (`BillPaymentSettler.placeHold`): idempotency claim, lock wallet, check rules,
   move money CUSTOMER -> BILLER_CLEARING (a hold), insert bill_payment `PENDING`. Commit.
2. Outside any tx: `biller.pay(reference, amount)`. The reference is our bill payment id, so retries
   at the biller are naturally idempotent.
3. Tx 2 (`BillPaymentSettler.apply`) by outcome:
   - Approved: entry BILLER_CLEARING -> BILLER_SETTLED, status `SUCCEEDED`.
   - Declined: reversing entry BILLER_CLEARING -> CUSTOMER, status `FAILED`.
   - Timeout / exception: status `UNKNOWN`, schedule a check. Money stays in clearing.
4. `BillPaymentReconciler` (scheduled) settles `PENDING` rows older than a threshold and all `UNKNOWN` rows
   by calling `biller.status(reference)`, which is the source of truth, then calls `BillPaymentSettler.apply`.

Correctness guards:
- State changes use conditional updates: `UPDATE ... SET status = ? WHERE id = ? AND status IN (...)`.
  If 0 rows updated, someone else already settled it; do nothing.
- `UNIQUE (reference_type, reference_id, type)` on `journal_entries` means a refund or settlement entry
  exists at most once per bill payment, even if two instances race.
- Multi-instance safety: the reconciler claims rows with `FOR UPDATE SKIP LOCKED` and a `locked_until` lease.
- Restart safety: anything left `PENDING`/`UNKNOWN` is picked up by the reconciler after restart.
- `BillerClient` is a port in main code. The fake lives only in `src/test`.

Fake biller (test code): outcomes 60% approved, 15% declined, 15% timeout-after-paying, 10% timeout-before-paying.
Driven by a fixed seed or scripted list, so runs are deterministic. `pay()` on an already paid reference returns
the original approval without paying again. `status()` always tells the truth. It records total amount received.

Required test: 200 bill payments, then run the reconciler until nothing is open. Assert every payment is
`SUCCEEDED` or `FAILED`, total debited from wallets (net of refunds) equals total the fake received,
no reference paid twice, no double refund, ledger invariants hold.

## 10. Notifications (outbox hook)

- Do not send real notifications. Show the hook.
- In the same transaction that completes a transfer (or a bill payment), insert an `outbox_events` row
  (`TRANSFER_COMPLETED`, payload with ids and amounts).
- A separate publisher reads unpublished rows (`FOR UPDATE SKIP LOCKED`), calls `NotificationSender`
  (logging stub), and marks them published.
- This guarantees: no notification for a rolled-back transfer, no missed notification for a committed one.
  Delivery is at-least-once; consumers dedupe by event id. Say this in the README.

## 11. API conventions

- Errors use RFC 9457 `ProblemDetail` with a stable machine `code` field. One `@RestControllerAdvice`.
- Status codes: 201 created, 200 ok, 202 accepted (bill payment still unknown), 400 malformed/validation,
  404 not found, 422 business rule (`INSUFFICIENT_FUNDS`, `WALLET_FROZEN`, `SYSTEM_ACCOUNT_NOT_ALLOWED`,
  `SAME_WALLET`, `IDEMPOTENCY_KEY_REUSED`).
- Customers cannot deposit into, send from or send to FEE, CASH_IN or any system account: 422.
- Request/response DTOs are Java `record`s with Bean Validation. Never expose domain objects directly.
- IDs: UUID (UUIDv7 for index locality, document it).
- Timestamps: `Instant`, stored as `TIMESTAMPTZ`, UTC. Inject `Clock`, never call `Instant.now()` directly.

## 12. Persistence and migrations

- Migrations in `src/main/resources/db/migration`, named `V1__accounts.sql`, etc.
- Never edit a migration that has been committed; add a new one.
- Constraints belong in the DB too (CHECK, UNIQUE, FK, NOT NULL), not only in Java.
- Index foreign keys and every column used in a lookup or reconciler query.

## 13. Testing

- Unit tests: `FeePolicy` (rounding at .5 boundaries, min 250, max 10,000, amount 1, amount 5,000,000),
  `Amount` validation, `Posting` zero-sum, `BillPaymentStatus` transitions.
- Integration tests: extend one `AbstractIntegrationTest` using a single shared PostgreSQL container
  (`@ServiceConnection`, static container) so the suite is fast. Clean tables between tests.
- Architecture tests: `ArchitectureTest` per section 4.5.
- Test names describe behaviour: `transferIsRejected_whenSenderFrozen`.
- Concurrency tests assert DB state and ledger invariants, not just HTTP codes.
- No `Thread.sleep` for synchronisation; use latches or Awaitility.
- No mocking of the database. The only test double is the fake biller.

## 14. Code style

- Constructor injection only, `final` fields, no field `@Autowired`.
- Small methods, intention-revealing names, no Lombok (records cover most cases). Keep dependencies minimal.
- Exceptions: one base `BusinessException` carrying an `ErrorCode`; no generic `RuntimeException` throws.
  Use exceptions only where 4.6 rule 4 allows; otherwise return a sealed result.
- Log with SLF4J, structured key=value, include ids (transfer id, idempotency key). Never log full request bodies.
- Comments explain WHY (especially around locking and idempotency), not what.
- Config in `application.yml`; local dev values only in `docker-compose.yml`. No real secrets anywhere.

## 15. Git workflow

- Small, focused commits with conventional messages (`feat: add fee policy with edge tests`).
- One logical step per commit; tests in the same commit as the code they cover.
- Do not squash history; reviewers read it.
- Do not commit `target/`, IDE files, or `.env` files.

## 16. How Claude should work in this repo

- Before coding a phase, propose a short plan and the files to change; wait for approval.
- Work one phase at a time. After each phase: run `./mvnw verify`, report results honestly, suggest a commit message.
- When the brief is silent and you make a call, append it to `docs/DECISIONS.md` (decision, alternatives, why).
  The README summarises this file.
- If something cannot be finished, say so plainly and write what is missing and how to do it.
- Do not invent requirements, endpoints, or features not in the brief.
- When unsure about a Spring Boot 4 API, check the docs rather than guessing from Boot 3 habits.
