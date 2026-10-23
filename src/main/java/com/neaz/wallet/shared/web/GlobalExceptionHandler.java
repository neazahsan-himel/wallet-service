package com.neaz.wallet.shared.web;

import com.neaz.wallet.shared.error.BusinessException;
import com.neaz.wallet.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Turns every error into an RFC 9457 {@link ProblemDetail} with a stable {@code code} property,
 * so clients can branch on the code instead of parsing messages.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        return problem(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(ErrorCode.VALIDATION_FAILED, detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleMalformed(Exception ex) {
        // The parser message can echo request content, so the client gets a generic detail.
        return problem(ErrorCode.MALFORMED_REQUEST, "request could not be read");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        // Spring MVC's own errors (unknown route, wrong method, ...) already carry a ProblemDetail; keep them.
        if (ex instanceof ErrorResponse springError) {
            return ResponseEntity.status(springError.getStatusCode())
                    .headers(springError.getHeaders())
                    .body(springError.getBody());
        }
        log.error("event=unexpected_error", ex);
        return problem(ErrorCode.INTERNAL_ERROR, "unexpected error");
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail) {
        HttpStatus status = HttpStatus.valueOf(code.httpStatus());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code.name());
        return ResponseEntity.status(status).body(body);
    }
}
