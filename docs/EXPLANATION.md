# How the code works (Phase 1: foundation)

Walk-through of everything added in Phase 1, written so you can explain any line on the review call.
Each section says **what** the code does, **why** it is done that way, and **what a reviewer might ask**.

---

## 1. The big picture

The brief judges five things: money handling, correctness under concurrency, failure behaviour, readable
code and tests. Phase 1 builds the foundation every later feature stands on:

```
docs/                     what we are building (BRIEF.md) and why we chose things (DECISIONS.md)
pom.xml                   dependencies + test plugins
docker-compose.yml        local PostgreSQL for running the app
application.yml           app config
db/migration/V1..V3       the database schema (Flyway)
shared/kernel             Amount, Currency, Ids     -> money and ids, used everywhere
shared/error              ErrorCode, BusinessException
transfer/domain           FeePolicy                 -> the fee rule
tests                     unit tests, one integration test, architecture rules
```

Nothing here moves money yet. Phase 1 makes sure that when money does move (Phase 2+), the database and the
types make wrong states hard or impossible.

---

## 2. Build: `pom.xml`

| Dependency | Why we need it |
|---|---|
| `spring-boot-starter-webmvc` | REST controllers (already there from Spring Initializr). |
| `spring-boot-starter-validation` | Bean Validation (`@NotNull`, `@Positive`) on request records later. |
| `spring-boot-starter-jdbc` | `DataSource`, transactions and `JdbcClient`. We write SQL by hand so locking (`FOR UPDATE`) is visible. **No JPA.** |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Runs the SQL migrations on startup. Since Flyway 10, PostgreSQL support is a separate module, so both are needed. |
| `postgresql` (runtime) | The JDBC driver. |
| `spring-boot-starter-webmvc-test` (test) | JUnit 5, AssertJ, Spring test support. |
| `spring-boot-testcontainers` (test) | `@ServiceConnection`: wires the datasource to the test container automatically. |
| `testcontainers-postgresql` (test) | Starts a real PostgreSQL in Docker for tests. Testcontainers 2.x renamed artifacts to `testcontainers-*`. |
| `awaitility` (test) | Waiting for async work (outbox, reconciler) without `Thread.sleep`. Used later. |
| `archunit-junit5` (test) | Tests that check the package structure rules. Not managed by Spring Boot, so the version is pinned. |

**The failsafe plugin.** Maven has two test runners:

- **surefire** runs `*Test` classes in the `test` phase: fast unit tests, no Docker.
- **failsafe** runs `*IT` classes in the `integration-test` phase, and fails the build in `verify`.

So `./mvnw test` is a quick check, and `./mvnw verify` runs everything. That is the "one documented command".

> **Reviewer might ask:** "Why JDBC and not JPA?"
> Answer: for money we need to see exactly which rows are locked and when. With `JdbcClient` the
> `SELECT ... FOR UPDATE` is right there in the code. JPA hides SQL, can flush at surprising times, and
> optimistic locking would turn the 50-parallel-transfers test into a retry storm.

---

## 3. Local database: `docker-compose.yml` and `application.yml`

`docker compose up -d` starts PostgreSQL 16 on port 5432 with user/password/db `wallet`. These are local dev
values, which the brief explicitly allows. The healthcheck lets tools wait until the database is ready.

`application.yml` points the app at that database. **Tests ignore these values**: `@ServiceConnection`
replaces them with the Testcontainers database's URL.

---

## 4. The schema (Flyway migrations)

Flyway runs `V1`, `V2`, `V3` in order on startup and records them in `flyway_schema_history`. A committed
migration is never edited; changes go in a new `V4__...` file. That way every environment has the same history.

### 4.1 `V1__accounts.sql`: one table for wallets and system accounts

Customer wallets and system accounts (FEE, CASH_IN, later biller accounts) live in **one** `accounts` table.
Reason: every ledger line points at an `account_id` with a real foreign key, whatever kind of account it is.

Column by column:

| Column | Notes |
|---|---|
| `id UUID` | UUIDv7, generated in Java (see `Ids`). |
| `owner_id VARCHAR(64)` | Who owns the wallet. Opaque string because owners live in another system (D6). |
| `type` | `CUSTOMER`, `FEE`, `CASH_IN`, `BILLER_CLEARING`, `BILLER_SETTLED`. |
| `currency CHAR(3)` | Always `IQD`, enforced by a CHECK. |
| `balance BIGINT` | Whole dinars. Never decimals (D2). |
| `status` | `ACTIVE` or `FROZEN`. |
| `created_at TIMESTAMPTZ` | Stored with time zone (UTC), so there's no "which time zone was that?" confusion. |

The constraints are business rules the **database** enforces, even if Java code has a bug:

- `accounts_currency_chk`: only IQD.
- `accounts_owner_chk`: `(type = 'CUSTOMER') = (owner_id IS NOT NULL)`. Both sides are booleans, so it
  reads: "it is a customer exactly when it has an owner". Customers must have an owner; system accounts must not.
- `accounts_customer_balance_chk`: a customer balance can never be negative. The app checks funds first under
  a row lock; this is the last line of defence (brief rule 5).
- `accounts_system_balance_chk`: system accounts keep `balance = 0`. Their real balance is the sum of their
  ledger lines. See 4.4 for why.

### 4.2 `V2__ledger.sql`: double-entry bookkeeping

Two tables:

- `journal_entries`: one row per money movement ("transfer X happened at 10:42").
- `ledger_lines`: two or more rows per entry, one per account touched, with a **signed** amount:
  negative = money leaves, positive = money arrives.

Example transfer of 10,000 with fee 250:

| account | amount |
|---|---|
| sender | -10,250 |
| receiver | +10,000 |
| FEE | +250 |
| **sum** | **0** |

Because every entry sums to zero, the sum of **all** lines is always zero. That is how finance proves "no
money created or lost" (brief rule 3) with one query: `SELECT SUM(amount) FROM ledger_lines` must be 0.

It also covers the "History" requirement: each line says which account, how much, which operation
(`entry.type`, `reference_type`, `reference_id`) and when (`created_at`).

**`journal_entries` columns worth explaining:**

- `reference_type` + `reference_id`: which business operation caused this entry (e.g. TRANSFER + transfer id).
- `reverses_entry_id`: a correction points at the entry it reverses. History is never edited; the fix is a
  new, visible entry ("If something needs fixing, the fix must be visible too").
- `UNIQUE (reference_type, reference_id, type)`: one bill payment can get at most one HOLD, one SETTLE and one
  REFUND entry. If two instances race to refund the same payment, the second INSERT fails. "No wallet is
  refunded twice" is then guaranteed by the database, not only by careful code.

**Append-only triggers.** `reject_ledger_change()` raises an error on any `UPDATE` or `DELETE` of either
ledger table. "Past records can never be changed" is enforced, not just promised.
(`TRUNCATE` does not fire row triggers; in production the app's DB user simply isn't granted `TRUNCATE`.)

**Zero-sum trigger (deferred).** `check_entry_balanced()` sums the lines of an entry and raises if the sum is
not 0. Key detail: it is a `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`. That means it runs at
**COMMIT**, not after each INSERT. It has to be that way: after inserting the first line (-10,250) the entry
is temporarily unbalanced. Only when all lines are in, at commit, must it be zero.
If it fails, the whole transaction rolls back, which also gives "all or nothing" (brief rule 4).

**Indexes.** PostgreSQL does not index foreign keys automatically. We index `entry_id` (fetch an entry's
lines), `account_id` (an account's history / derived balance) and `reverses_entry_id`.

### 4.3 `V3__seed_system_accounts.sql`

Inserts FEE and CASH_IN with **fixed ids** (`...0001`, `...0002`) so Java code and finance SQL can refer to
them directly. The ids are valid UUIDv7-format values (`7` version digit, `8` variant digit).
Biller accounts are added later with the bill payment feature, so this commit doesn't contain unused data.

### 4.4 Why system account balances are derived (D7): the "hot row" problem

Every transfer pays a fee into the **same** FEE account. If we did `UPDATE accounts SET balance = balance + 250
WHERE id = FEE` on each transfer, that row would be locked until each transaction commits. **Every transfer in
the whole system would queue behind one row.** So:

- **Customer** balances are stored and updated, because we *need* to lock that row to check funds safely.
- **System** balances are computed when needed: `SELECT SUM(amount) FROM ledger_lines WHERE account_id = FEE`.
  Inserting lines needs no lock on the FEE row, so transfers run in parallel.

Trade-off: reading the FEE balance gets slower as history grows. Fix later with periodic snapshots.

> **Reviewer might ask:** "CASH_IN goes negative. Is that a bug?"
> No. CASH_IN is the accounting side of cash agents took in. Depositing 1,000 is CASH_IN -1,000,
> customer +1,000. Its negative balance equals the total e-money issued. That's why the
> "never below zero" CHECK only applies to `CUSTOMER`.

---

## 5. Shared kernel (`shared/kernel`, `shared/error`)

### 5.1 `Amount`

```java
public record Amount(long value) {
    public Amount {                       // compact constructor: runs on every creation
        if (value < MIN || value > MAX) throw new BusinessException(INVALID_AMOUNT, ...);
    }
}
```

- `long` whole dinars, never `double` (`0.1 + 0.2 != 0.3` in floating point).
- **"Parse, don't validate":** if you hold an `Amount`, it is valid. Code receiving one never re-checks.
- The 1..5,000,000 limit applies to deposits and bill payments too (D3).
- It's a `record`, so equality is by value: `Amount.of(100).equals(Amount.of(100))`.

Rejecting `100.5`, `"100"` or `null` in JSON is the job of Jackson configuration and Bean Validation at the
HTTP edge (Phase 2). `Amount` guards everything after that.

### 5.2 `Currency`

A one-value enum (`IQD`). It makes the single-currency assumption visible in code. The DB enforces it too.

### 5.3 `Ids`: UUIDv7

A UUID is 128 bits. Version 7 layout:

```
| 48 bits: Unix time in ms | 4 bits: version=7 | 12 bits random | 2 bits: variant=10 | 62 bits random |
```

In code:

```java
long mostSig  = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
long leastSig = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
```

- `millis << 16` puts the 48-bit timestamp in the top bits.
- `0x7000` sets the version nibble to 7.
- `& 0x0FFF` keeps 12 random bits.
- `& 0x3FFF...` clears the top 2 bits, then `| 0x8000...` sets them to `10` (the RFC variant).

**Why v7 instead of `UUID.randomUUID()` (v4)?** v4 is fully random, so each insert lands on a random page of
the primary key index: more page splits and cache misses. v7 ids grow over time, so inserts append at the
end of the index, almost like a sequence. They can still be generated in Java before the insert and
aren't guessable. Java 21 has no v7 generator, so we wrote one (a few lines, tested in `IdsTest`).

### 5.4 `ErrorCode` and `BusinessException`

One exception type carrying a stable code (`INVALID_AMOUNT`, more later). A `@RestControllerAdvice` (Phase 2)
turns it into an RFC 9457 `ProblemDetail` response with that code and HTTP status.
The status is a plain `int`, not Spring's `HttpStatus`, so domain code can use these without importing Spring.

---

## 6. `FeePolicy`

Rule: 0.5% of the amount, rounded HALF_UP to whole dinars, minimum 250, maximum 10,000.

```java
long percentageFee = (amount.value() + 100) / 200;
return Math.clamp(percentageFee, MIN_FEE, MAX_FEE);
```

**Why this formula works:**

- 0.5% = 5/1000 = **1/200**. So the exact fee is `amount / 200`.
- Java integer division **truncates** (rounds down). To round HALF_UP instead, add half the divisor first:
  `(amount + 100) / 200`.
- Check: amount 50,100 gives 250.5, which should round **up** to 251. `(50,100 + 100) / 200 = 50,200 / 200 = 251`. ✔
- Check: amount 50,099 gives 250.495, which should round **down** to 250. `50,199 / 200 = 250` (truncated). ✔
- Then `Math.clamp` applies the 250 / 10,000 limits.

This only works for positive numbers, which is guaranteed because `Amount` is always >= 1.
Max amount 5,000,000 + 100 is far below `Long.MAX_VALUE`, so no overflow.

**Where the limits kick in:**

- Amounts up to 50,099 pay the minimum 250.
- Amounts from 1,999,900 pay the maximum 10,000.

`FeePolicy` has no Spring annotations. It is plain Java and lives in `transfer/domain`.

> **Reviewer might ask:** "Why not BigDecimal?"
> Both are correct. Integer arithmetic is exact, allocation-free and easy to reason about. To prove they
> agree, `FeePolicyTest` compares our formula to `BigDecimal ... setScale(0, HALF_UP)` for ~714,000
> amounts across the full range (every 7th value from 1 to 5,000,000).

---

## 7. Tests

### 7.1 Unit tests (surefire, no Docker)

- **`AmountTest`**: accepts 1, 5,000,000 and values between; rejects 0, negatives, 5,000,001,
  `Long.MAX_VALUE` / `MIN_VALUE`; checks the error code is `INVALID_AMOUNT`.
- **`IdsTest`**: version is 7, variant is 2 (RFC), the embedded timestamp is "now", 10,000 ids are unique.
- **`FeePolicyTest`**: named edge cases (1, minimum boundary, .5 rounding both ways, maximum boundary,
  5,000,000) plus the BigDecimal cross-check.

### 7.2 Integration test base: `AbstractIntegrationTest`

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
public abstract class AbstractIntegrationTest {
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static { POSTGRES.start(); }
}
```

- **Static container, started in a static block:** one PostgreSQL per test run, shared by every `*IT`.
  Starting a container takes seconds; doing it once keeps the suite fast.
- **`@ServiceConnection`:** Spring Boot reads the container's JDBC URL, user and password and configures the
  datasource. No hand-written properties.
- **Same configuration in every subclass**, so Spring reuses one application context.
- **`RANDOM_PORT`:** later tests make real HTTP calls; a random port avoids clashes.
- Flyway runs on startup, so tests use the **real schema with all triggers**. No H2, as the brief demands.

### 7.3 `SchemaMigrationIT`

Proves the database itself keeps its promises:

1. FEE and CASH_IN are seeded.
2. Inserting a customer with balance -1 fails with `accounts_customer_balance_chk`.
3. An entry with lines -100 and +99, committed in one transaction, fails with "does not balance".
   It uses `TransactionTemplate` so both lines are in one transaction and the deferred trigger fires at commit.
4. After writing a balanced entry, `UPDATE` and `DELETE` fail with "append-only".

Note: test 4 leaves a balanced -100/+100 entry on CASH_IN/FEE that can never be deleted (that's the point).
Later tests therefore assert **changes** in system account balances, not absolute values. Global invariants
(sum of all lines = 0) still hold.

### 7.4 `ArchitectureTest` (ArchUnit)

Reads the compiled classes and fails the build if the structure rules from `CLAUDE.md` 4.5 are broken:

| Rule | Why |
|---|---|
| `domain` doesn't use Spring, `java.sql` or Jackson | Domain stays plain Java, unit-testable without a container. |
| `api` doesn't use `infrastructure` | Controllers go through services, never straight to repositories. |
| `@Transactional` only in `application` (classes and methods) | Transaction boundaries live in one known place: the use-case method. |
| `ledger`/`account` don't depend on `transfer`/`deposit`/`billpayment` | Core modules stay independent of the features that use them. |
| A feature never uses another feature's `api` or `infrastructure` | Features talk only through each other's application services. |
| No class name ends with `Impl` | Naming rule: adapters are named by technology (`JdbcXRepository`). |

`allowEmptyShould(true)`: ArchUnit normally fails a rule that matches no classes. Most packages don't exist
yet, so empty rules must pass for now; they start biting as soon as code appears.

---

## 8. Commands

```bash
docker compose up -d     # local database for running the app
./mvnw spring-boot:run   # run the service on port 8088
./mvnw test              # unit + architecture tests only (no Docker needed)
./mvnw verify            # everything, including integration tests (needs Docker running)
```

---

## 9. Quick answers for the call

- **How do you prove the books balance?** Every entry's lines sum to 0 (checked in Java and by a deferred
  DB trigger at commit), so `SUM(ledger_lines.amount) = 0` overall, and each customer balance equals the sum
  of its lines.
- **How is history immutable?** DB triggers reject UPDATE/DELETE on ledger tables; fixes are reversing entries
  linked by `reverses_entry_id`.
- **Why can't a balance go negative?** The app checks funds while holding a row lock (Phase 2), and a CHECK
  constraint rejects a negative customer balance anyway.
- **Why integers for money?** IQD here is whole dinars; integers are exact and overflow can be checked with
  `Math.addExact`.
- **Why UUIDv7?** Time-ordered, so index inserts stay local; still generated in the app and not guessable.
- **What is not done yet?** Endpoints, locking, idempotency, outbox and bill payments: Phases 2+.
