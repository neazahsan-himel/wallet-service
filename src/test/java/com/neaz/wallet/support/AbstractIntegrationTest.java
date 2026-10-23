package com.neaz.wallet.support;

import com.neaz.wallet.account.application.AccountService;
import com.neaz.wallet.ledger.application.LedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests: the full application against a real PostgreSQL.
 *
 * <p>The container is static and started once per JVM, so every IT shares one database and the suite stays fast.
 * Spring caches the application context across subclasses because they share the same configuration.
 * {@code @ServiceConnection} points the datasource at the container; no hand-written properties.
 *
 * <p>Every test starts from empty tables (system accounts kept) and ends by checking the ledger invariants,
 * so no test can leave the books unbalanced without failing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TransactionTemplate tx;

    @Autowired
    protected RestTestClient http;

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected LedgerService ledgerService;

    protected TestDataFactory data;

    @BeforeEach
    void cleanDatabase() {
        // TRUNCATE is not a row-level UPDATE/DELETE, so the append-only triggers do not block it.
        // In production the application role would not be granted TRUNCATE (see DECISIONS D8).
        jdbc.sql("TRUNCATE ledger_lines, journal_entries, idempotency_keys, transfers").update();
        jdbc.sql("DELETE FROM accounts WHERE type = 'CUSTOMER'").update();
        data = new TestDataFactory(accountService, ledgerService, jdbc, tx);
    }

    @AfterEach
    void ledgerInvariantsHold() {
        LedgerInvariants.assertHold(jdbc);
    }
}
