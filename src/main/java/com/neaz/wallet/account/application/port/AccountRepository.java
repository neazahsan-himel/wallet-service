package com.neaz.wallet.account.application.port;

import com.neaz.wallet.account.domain.Account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    void insert(Account account);

    Optional<Account> findById(UUID id);

    /** Locks the rows with {@code SELECT ... FOR UPDATE} in ascending id order and returns the ones that exist. */
    List<Account> lockByIds(Collection<UUID> ids);
}
