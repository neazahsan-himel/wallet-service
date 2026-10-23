package com.neaz.wallet.transfer.api;

import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.web.IdempotencyHeader;
import com.neaz.wallet.transfer.application.TransferResult;
import com.neaz.wallet.transfer.application.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/api/v1/transfers")
    ResponseEntity<TransferResponse> transfer(@RequestHeader(IdempotencyHeader.NAME) String idempotencyKey,
                                              @Valid @RequestBody TransferRequest body) {
        return toResponse(transfers.transfer(body.toCommand(idempotencyKey)));
    }

    /**
     * The transaction has already committed here. A rejection was stored for replay and is only now turned
     * into an error response, so throwing cannot roll anything back.
     */
    private static ResponseEntity<TransferResponse> toResponse(TransferResult result) {
        return switch (result) {
            case TransferResult.Completed completed ->
                    ResponseEntity.status(HttpStatus.CREATED).body(TransferResponse.from(completed));
            case TransferResult.Rejected rejected -> throw new BusinessException(rejected.code(), rejected.reason());
        };
    }
}
