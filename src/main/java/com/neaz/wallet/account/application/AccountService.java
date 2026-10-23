package com.neaz.wallet.account.application;

import com.neaz.wallet.account.application.port.AccountRepository;
import com.neaz.wallet.account.domain.Account;
import com.neaz.wallet.account.domain.AccountPolicy;
import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;
import com.neaz.wallet.shared.kernel.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final Clock clock;

    public AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public Account create(String ownerId) {
        var account = Account.newCustomerWallet(Ids.newId(), ownerId, clock.instant());
        accounts.insert(account);
        log.info("event=wallet_created walletId={}", account.id());
        return account;
    }

    /** Returns a customer wallet. System accounts are not wallets, so their ids are reported as not found. */
    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id)
                .filter(AccountPolicy::isCustomer)
                .orElseThrow(() -> notFound(id));
    }

    /**
     * Locks the given accounts until the surrounding transaction ends. MANDATORY because a row lock taken
     * outside a use-case transaction would be released immediately and protect nothing.
     * Rows are locked in ascending id order (see the repository) so two transactions locking the same pair
     * always queue instead of deadlocking.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockForUpdate(UUID... ids) {
        Set<UUID> wanted = new HashSet<>(Arrays.asList(ids));
        Map<UUID, Account> locked = accounts.lockByIds(wanted).stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        for (UUID id : wanted) {
            if (!locked.containsKey(id)) {
                throw notFound(id);
            }
        }
        return locked;
    }

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.WALLET_NOT_FOUND, "wallet " + id + " not found");
    }
}
