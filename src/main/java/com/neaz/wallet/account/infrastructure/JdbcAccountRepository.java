package com.neaz.wallet.account.infrastructure;

import com.neaz.wallet.account.application.port.AccountRepository;
import com.neaz.wallet.account.domain.Account;
import com.neaz.wallet.account.domain.AccountStatus;
import com.neaz.wallet.account.domain.AccountType;
import com.neaz.wallet.shared.kernel.Currency;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final RowMapper<Account> ROW_MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("owner_id"),
            AccountType.valueOf(rs.getString("type")),
            Currency.valueOf(rs.getString("currency")),
            rs.getLong("balance"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Account account) {
        jdbc.sql("""
                        INSERT INTO accounts (id, owner_id, type, currency, balance, status, created_at)
                        VALUES (:id, :ownerId, :type, :currency, :balance, :status, :createdAt)
                        """)
                .param("id", account.id())
                .param("ownerId", account.ownerId())
                .param("type", account.type().name())
                .param("currency", account.currency().name())
                .param("balance", account.balance())
                .param("status", account.status().name())
                .param("createdAt", OffsetDateTime.ofInstant(account.createdAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT * FROM accounts WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public List<Account> lockByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // One statement with ORDER BY: PostgreSQL takes the row locks in that order, using its own uuid
        // ordering. Every caller goes through here, so all transactions lock in the same global order.
        return jdbc.sql("SELECT * FROM accounts WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids)
                .query(ROW_MAPPER)
                .list();
    }
}
