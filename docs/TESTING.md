# Testing the wallet service

There are two ways to test it:

1. **Automated tests:** one command, no setup besides Docker. This is the real proof.
2. **Manual testing** against the running service, with the Postman collection in `docs/postman/`.

Commands are shown for Windows PowerShell. On macOS or Linux, use `./mvnw` instead of `.\mvnw.cmd`.

## Prerequisites

- JDK 21 (`java -version`)
- Docker Desktop, running

## 1. Automated tests

```powershell
.\mvnw.cmd verify
```

Testcontainers starts its own PostgreSQL 16 on a random port, so nothing else needs to be running. The first
run downloads dependencies and the image. The run passes when it ends with `BUILD SUCCESS`.

| Test | What it proves |
|---|---|
| `FeePolicyTest` | 0.5% fee, HALF_UP at the .5 edges, min 250, max 10,000, amounts 1 and 5,000,000 |
| `AmountTest`, `PostingTest`, `BillPaymentStatusTest`, `*PolicyTest` | Domain rules, without a database |
| `TransferConcurrencyIT` | 50 parallel transfers of 10,000 from 300,000: exactly 29 succeed, 2,750 left |
| `TransferRetryIT` | The same transfer sent 10 times in parallel produces 1 transfer, 1 journal entry and correct balances |
| `DepositConcurrencyIT`, `AccountLockingIT` | Concurrent deposits and opposite transfers don't lose updates or deadlock |
| `BillPaymentReconciliationIT` | 200 payments through a flaky fake biller, then the reconciler runs. Every payment ends SUCCEEDED or FAILED, the money matches, nothing is paid or refunded twice |
| `BillPaymentRecoveryIT`, `BillPaymentRetryIT` | Restarts, timeouts and retries of bill payments |
| `OutboxPublisherIT` | A committed transfer is always notified; a rolled-back one never is |
| `ArchitectureTest` | Layering rules (see README, Architecture) |

Every integration test ends by checking that the ledger sums to 0 and that each wallet balance equals the sum
of its ledger lines.

Run a single test:

```powershell
.\mvnw.cmd test   "-Dtest=FeePolicyTest"               # one unit test
.\mvnw.cmd verify "-Dit.test=TransferConcurrencyIT"    # one integration test
```

Reports are written to `target\surefire-reports` (unit tests) and `target\failsafe-reports` (integration tests).

## 2. Manual testing with Postman

### Step 1: start the database and the service

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

Wait for `Started WalletServiceApplication`. The service listens on `http://localhost:8088`.

### Step 2: import and run the collection

1. In Postman, click **Import** and choose `docs/postman/wallet-service.postman_collection.json`.
2. Right-click the collection, choose **Run collection**, then click **Run**.

Run the whole collection in order. Folder `0. Setup` creates four wallets, and later requests use their ids
and check exact balances. Every run uses fresh wallets and fresh idempotency keys, so you can run it again
at any time.

Each request name ends with its expected result, for example `[201]` or `[422 INSUFFICIENT_FUNDS]`. A shared
test checks the HTTP status and the error `code`. Many requests also check fees and balances.

| Folder | Cases |
|---|---|
| 1. Wallets | Get, unknown id (404), system account hidden (404), bad UUID, missing/blank/long `ownerId`, broken JSON |
| 2. Deposits | Success, retry not doubled, same key with different body (422), amount limits 1 and 5,000,000, rejected amounts (0, negative, over the limit, `100.5`, `"1000"`, `null`), missing or long key, unknown wallet, system accounts |
| 3. Transfers | Fee 250 / 251 (rounding) / 500 / 10,000 (clamp), retry, reused key, insufficient funds, stored rejection replayed after funding, sending the exact whole balance, same wallet, system accounts, unknown wallet, validation, final balances |
| 4. Bill payments | Success with no fee, retry paid once, reused key, insufficient funds, system account, unknown wallet, validation |
| 5. Frozen wallet | Can't transfer or pay bills (422 `WALLET_FROZEN`), can still receive transfers and deposits |

**Folder 5 needs one manual step.** The brief has no endpoint to freeze a wallet, so do it in the database.
Copy `walletB` from the collection's **Variables** tab, then run:

```powershell
docker exec wallet-service-postgres-1 psql -U wallet -d wallet -c "UPDATE accounts SET status = 'FROZEN' WHERE id = '<walletB>'"
```

The local service uses `LoggingBillerClient`, which approves every payment, so manual bill payments always end
`SUCCEEDED`. Declines, timeouts (202 `UNKNOWN`) and reconciliation are covered by the automated tests.

### Step 3: check the ledger yourself

```powershell
docker exec wallet-service-postgres-1 psql -U wallet -d wallet -c "SELECT SUM(amount) FROM ledger_lines"
```

The result must be `0`.

### Step 4: clean up

Press `Ctrl+C` to stop the service, then:

```powershell
docker compose down      # stop the database and keep its data
docker compose down -v   # stop it and delete the data
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `password authentication failed for user "wallet"` | Another PostgreSQL (for example, one installed on Windows) is also using port 5432 and gets the connection | Stop it while testing: `services.msc`, then stop `postgresql-x64-NN` (needs admin). `.\mvnw.cmd verify` isn't affected |
| `Migration checksum mismatch` | The local Docker volume was created from an older schema | `docker compose down -v`, then `docker compose up -d` (deletes local dev data only) |
| `Could not find a valid Docker environment` during `verify` | Docker Desktop isn't running | Start it and wait until it reports running |
| `Port 8088 was already in use` | The service is already running in another window | Stop that one, or use the running instance |
