package com.neaz.wallet.billpayment.api;

import com.neaz.wallet.billpayment.application.BillPaymentResult;
import com.neaz.wallet.billpayment.application.BillPaymentService;
import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.web.IdempotencyHeader;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BillPaymentController {

    private final BillPaymentService payments;

    public BillPaymentController(BillPaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/api/v1/bill-payments")
    ResponseEntity<BillPaymentResponse> pay(@RequestHeader(IdempotencyHeader.NAME) String idempotencyKey,
                                            @Valid @RequestBody BillPaymentRequest body) {
        return toResponse(payments.pay(body.toCommand(idempotencyKey)));
    }

    /**
     * 201 once the outcome is final (SUCCEEDED, or FAILED with the wallet refunded): the payment record exists either
     * way and the body says which. 202 while the biller's answer is still unknown. Rejections were stored for
     * replay before reaching here, so throwing cannot roll anything back.
     */
    private static ResponseEntity<BillPaymentResponse> toResponse(BillPaymentResult result) {
        return switch (result) {
            case BillPaymentResult.Processed processed -> ResponseEntity
                    .status(processed.status().isOpen() ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                    .body(BillPaymentResponse.from(processed));
            case BillPaymentResult.Rejected rejected -> throw new BusinessException(rejected.code(), rejected.reason());
        };
    }
}
