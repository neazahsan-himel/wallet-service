package com.neaz.wallet.transfer.infrastructure;

import com.neaz.wallet.transfer.application.port.TransferRepository;
import com.neaz.wallet.transfer.domain.Transfer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class JdbcTransferRepository implements TransferRepository {

    private final JdbcClient jdbc;

    public JdbcTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Transfer transfer) {
        jdbc.sql("""
                        INSERT INTO transfers (id, from_wallet_id, to_wallet_id, amount, fee, created_at)
                        VALUES (:id, :fromWalletId, :toWalletId, :amount, :fee, :createdAt)
                        """)
                .param("id", transfer.id())
                .param("fromWalletId", transfer.fromWalletId())
                .param("toWalletId", transfer.toWalletId())
                .param("amount", transfer.amount())
                .param("fee", transfer.fee())
                .param("createdAt", OffsetDateTime.ofInstant(transfer.createdAt(), ZoneOffset.UTC))
                .update();
    }
}
