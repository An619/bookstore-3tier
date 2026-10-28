package com.anas.bookstore.orders;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout entry point. It records the order, the idempotency outcome and an
 * outbox event in ONE transaction, then returns 202: the inventory reservation
 * is performed asynchronously by {@link CheckoutWorker} and retried until it
 * succeeds, which removes the "reserved stock but lost the order" crash window.
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {
    /** 8-48 characters, so the worker can append ":reserve" and stay under 64. */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,48}");

    private final CheckoutAttemptRepository attempts;
    private final OrdersRepository orders;
    private final OutboxRepository outbox;

    public CheckoutController(CheckoutAttemptRepository attempts, OrdersRepository orders, OutboxRepository outbox) {
        this.attempts = attempts;
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional
    @PostMapping
    public ResponseEntity<?> checkout(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody(required = false) OrdersController.CreateOrder request
    ) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            return error(HttpStatus.BAD_REQUEST,
                "A valid Idempotency-Key header (8-48 letters, digits or . _ : -) is required");
        }
        String validationError = validate(request);
        if (validationError != null) {
            return error(HttpStatus.BAD_REQUEST, validationError);
        }

        Optional<CheckoutAttempt> existing = attempts.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        OrderRecord order = orders.save(new OrderRecord(
            request.customerName().trim(), request.bookId(), request.quantity(), OrderRecord.PENDING));
        // Same transaction: the intent to reserve stock cannot be lost.
        outbox.save(new OutboxEvent(idempotencyKey, OutboxEvent.RESERVE_STOCK,
            request.bookId(), request.quantity()));
        attempts.saveAndFlush(new CheckoutAttempt(
            idempotencyKey, order.getId(), CheckoutAttempt.PENDING, 202, "Stock reservation queued"));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .header("Idempotency-Replayed", "false")
            .body(order);
    }

    /**
     * Two concurrent requests with the same key: the loser's transaction is
     * rolled back by the database, and the caller gets a retryable conflict.
     * Handled outside the transaction so the rollback stays clean.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> duplicateKey(DataIntegrityViolationException ignored) {
        return error(HttpStatus.CONFLICT, "A checkout with this key is already being processed");
    }

    /** Replays the recorded outcome; never reserves stock again. */
    private ResponseEntity<?> replay(CheckoutAttempt attempt) {
        if (attempt.getOrderId() == null) {
            return error(HttpStatus.CONFLICT, "A checkout with this key is being recorded; retry shortly");
        }
        Optional<OrderRecord> order = orders.findById(attempt.getOrderId());
        if (order.isEmpty()) {
            return error(HttpStatus.CONFLICT, "The original order no longer exists");
        }
        if (CheckoutAttempt.FAILED.equals(attempt.getStatus())) {
            return error(HttpStatus.valueOf(attempt.getResponseStatus()),
                attempt.getMessage() == null ? "Checkout failed" : attempt.getMessage());
        }
        if (CheckoutAttempt.NEEDS_RECONCILIATION.equals(attempt.getStatus())) {
            return error(HttpStatus.CONFLICT,
                "A previous attempt is being reconciled automatically; retry shortly");
        }
        // PENDING or COMPLETED: report the current state of the same order.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .header("Idempotency-Replayed", "true")
            .body(order.get());
    }

    private static String validate(OrdersController.CreateOrder request) {
        if (request == null) {
            return "A JSON checkout body is required";
        }
        if (request.customerName() == null || request.customerName().isBlank()) {
            return "customerName is required";
        }
        if (request.customerName().trim().length() > 160) {
            return "customerName must be at most 160 characters";
        }
        if (request.bookId() == null || request.bookId() < 1) {
            return "bookId must be positive";
        }
        if (request.quantity() == null || request.quantity() < 1) {
            return "quantity must be positive";
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
