package com.realtimetradeprocessing.simulator.api;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.realtimetradeprocessing.simulator.domain.DomainException;
import com.realtimetradeprocessing.simulator.observability.CorrelationIdFilter;
import com.realtimetradeprocessing.simulator.observability.TradeMetrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    private final TradeMetrics tradeMetrics;

    public GlobalApiExceptionHandler(TradeMetrics tradeMetrics) {
        this.tradeMetrics = tradeMetrics;
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        DomainException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ApiErrorResponse> handleValidation(Exception exception, HttpServletRequest request) {
        tradeMetrics.orderRejected();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", validationMessage(exception), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> handleConflict(IdempotencyConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled API exception path={} correlationId={}", request.getRequestURI(), correlationId(request), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
    }

    private static ResponseEntity<ApiErrorResponse> error(
        HttpStatus status,
        String errorCode,
        String message,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
            Instant.now(),
            status.value(),
            errorCode,
            message,
            request.getRequestURI(),
            correlationId(request)
        ));
    }

    private static String validationMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException invalidException) {
            return invalidException.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(GlobalApiExceptionHandler::fieldErrorMessage)
                .collect(Collectors.joining("; "));
        }
        if (exception instanceof MissingRequestHeaderException missingHeaderException) {
            return "Missing required header: " + missingHeaderException.getHeaderName();
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return "Malformed or invalid JSON request";
        }
        return exception.getMessage();
    }

    private static String fieldErrorMessage(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private static String correlationId(HttpServletRequest request) {
        Object attribute = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        String header = request.getHeader(CorrelationIdFilter.HEADER_NAME);
        if (header == null || header.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return header.trim();
    }
}
