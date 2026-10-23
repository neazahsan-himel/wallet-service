package com.neaz.wallet.account.api;

import com.neaz.wallet.account.application.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final AccountService accounts;

    public WalletController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    ResponseEntity<WalletResponse> create(@Valid @RequestBody CreateWalletRequest body) {
        var wallet = accounts.create(body.ownerId());
        return ResponseEntity.created(URI.create("/api/v1/wallets/" + wallet.id()))
                .body(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    WalletResponse get(@PathVariable UUID id) {
        return WalletResponse.from(accounts.get(id));
    }
}
