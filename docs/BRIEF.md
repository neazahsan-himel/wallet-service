# Senior Software Engineer: Technical Assessment (Newroz Technologies Limited)

Transcribed from the original PDF. This is the source of truth; if `CLAUDE.md` disagrees, this file wins.

## Overview

Take the time you need within 5 days. Shortlisted candidates are invited to a call to walk through the work
and talk about design, scale and failure.

**What we look at:** how you handle money, how you keep data correct under concurrent load, how your service
behaves when things go wrong, how clean and readable your code is, how you test, and how you explain your trade-offs.

**What we don't look at:** UI, authentication, cloud setup, or extra features beyond the brief.

**Tools:** use whatever you normally use, AI assistants included. On the call we'll ask you to explain any line
of your code, so make sure you own it.

**The golden rule:** we value finished and honest over big and broken. If you can't finish something, just tell
us what's missing and how you'd do it.

**Questions:** if anything is unclear, reply to the email. If you'd rather not wait, make a sensible assumption
and write it down in your README.

## The task: build a mini wallet service

A small REST service that holds wallets in Iraqi dinar (IQD) and moves money between them. It must stay correct
when many requests hit the same wallet at once.

### Stack

Start from an empty project and set it up the way you would for a real service.

- Java 21, Spring Boot 4.x, Maven or Gradle.
- MySQL 8 or PostgreSQL 15+. No in-memory database. Tests run against the real thing.
- Schema migrations with Flyway or Liquibase.
- Integration tests with Testcontainers.
- One documented command runs the tests. Docker Compose for the database is enough; no need to containerise the app.

### Domain

- **Wallet:** id, owner id, currency (IQD only), balance, status (`ACTIVE` or `FROZEN`), created at.
- **Fee account:** one system account, seeded by a migration, that collects all transfer fees.
- **Cash-in account:** one system account, seeded by a migration. Every deposit is funded from it. Think of it as
  the accounting side of the cash an agent takes in, not a real bank balance.
- **History:** finance must be able to trace every dinar, including which operation moved it, from where, to
  where, and when. Past records can never be changed. If something needs fixing, the fix must be visible too.

### Endpoints

| Method | Path                            | Purpose                                 |
|--------|---------------------------------|-----------------------------------------|
| POST   | /api/v1/wallets                 | Create a wallet (starts at zero balance)|
| POST   | /api/v1/wallets/{id}/deposits   | Top up a wallet (cash-in)               |
| POST   | /api/v1/transfers               | Send money from one wallet to another   |
| GET    | /api/v1/wallets/{id}            | Wallet details and balance              |

That's all. No history or lookup endpoints.

### Business rules (must hold)

1. **Amounts** are whole dinars, greater than 0, at most 5,000,000 IQD per transfer. Reject anything else.
2. **Fee** is 0.5% of the amount, rounded `HALF_UP` to whole dinars. Minimum 250 IQD, maximum 10,000 IQD.
   The sender pays amount + fee.
3. **No money is created or lost.** Every operation must balance: money that leaves one place arrives somewhere
   else, fees and deposits included. At any point, finance must be able to prove the books add up.
4. **All or nothing.** An operation happens completely or not at all. A crash halfway must never leave a debit
   without its credit, or a fee without its transfer.
5. **A balance never goes below zero**, even with many requests at once.
6. **Retries are safe.** Mobile apps and agent systems retry when a request times out, sometimes several times at
   once. A retried deposit, transfer or bill payment must never move money twice.
7. A `FROZEN` wallet cannot send money. No endpoint is needed to freeze a wallet; set the status directly in the
   database for tests.
8. Customers can't send to, send from, or deposit into the fee or cash-in accounts.

### Tests we expect

- Unit tests for the fee rule, including rounding and the min/max edges.
- **Concurrency test:** a wallet holds 300,000 IQD. Fire 50 parallel transfers of 10,000 IQD from it. Exactly 29
  must succeed and the final balance must be 2,750 IQD.
- **Retry test:** send the same transfer 10 times in parallel, the way a retrying client would. Money moves
  exactly once. Check the database, not just the responses: one transfer, one set of records, and the right
  balances everywhere.

## Extension: bill payment with a flaky biller

This is part of the task. Add `POST /api/v1/bill-payments`. It takes money from a wallet and pays a biller
(say an electricity company) through an external API. No fee on bill payments.

The biller is unreliable. Write a fake biller in test code. On each `pay(reference, amount)` call it does one of:

| Outcome              | Chance | What really happened              |
|----------------------|--------|-----------------------------------|
| Approved             | 60%    | Bill paid                         |
| Declined             | 15%    | Not paid                          |
| Timeout after paying | 15%    | Bill paid, but you never hear back|
| Timeout before paying| 10%    | Not paid                          |

The fake also has `status(reference)`, which always tells the truth. The biller never pays the same reference
twice: calling `pay()` again for an already paid reference returns the original approval and does not pay again.
Drive the fake from a fixed random seed or a scripted list of outcomes, so the test is deterministic.

When the biller times out you can't tell whether the bill was paid. The service must still end up correct:

1. Once things settle, no wallet is charged for an unpaid bill, and no bill is paid without the wallet being charged.
2. No bill is paid twice, and no wallet is refunded twice.
3. This holds even if the service restarts mid-payment, or runs as several instances at once.

**Test:** run 200 bill payments through the fake, then let the service settle any open ones. Every payment ends
either succeeded or failed. The total taken from wallets equals the total the biller says it received.
No bill is paid twice.

## Out of scope

Auth, UI, currency exchange and real notifications. But do show where a "transfer completed" notification would
hook in. A customer must never be told about a transfer that didn't happen, or miss one that did.

## Where the brief is silent

Wherever you had to make a call, write down what you decided and why in your README.

## Submission

A single zip within 5 days, including the `.git` folder.

- Commit as you normally would. The history is read, not just the final state.
- `README.md`, about two pages at most:
  - How to run the service and the tests, in one command each.
  - The calls you made where the brief was silent, and why.
  - The key design choices and what you traded off.
  - Known gaps, and what you'd do with one more day.
  - Roughly how many hours you spent.
- No real secrets in the repo. Local dev values in `docker-compose.yml` are fine.
