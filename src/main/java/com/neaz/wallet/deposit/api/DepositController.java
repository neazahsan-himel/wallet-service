package com.neaz.wallet.deposit.api;

import com.neaz.wallet.deposit.application.DepositResult;
import com.neaz.wallet.deposit.application.DepositService;
import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.web.IdempotencyHeader;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class DepositController {

    private final DepositService deposits;

    public DepositController(DepositService deposits) {
        this.deposits = deposits;
    }

    @PostMapping("/api/v1/wallets/{id}/deposits")
    ResponseEntity<DepositResponse> deposit(@PathVariable UUID id,
                                            @RequestHeader(IdempotencyHeader.NAME) String idempotencyKey,
                                            @Valid @RequestBody DepositRequest body) {
        return toResponse(deposits.deposit(body.toCommand(id, idempotencyKey)));
    }

    /**
     * The transaction has already committed here. A rejection was stored for replay and is only now turned
     * into an error response, so throwing cannot roll anything back.
     */
    private static ResponseEntity<DepositResponse> toResponse(DepositResult result) {
        return switch (result) {
            case DepositResult.Completed completed ->
                    ResponseEntity.status(HttpStatus.CREATED).body(DepositResponse.from(completed));
            case DepositResult.Rejected rejected -> throw new BusinessException(rejected.code(), rejected.reason());
        };
    }
}
