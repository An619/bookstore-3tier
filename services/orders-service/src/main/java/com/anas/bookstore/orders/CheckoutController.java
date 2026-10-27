package com.anas.bookstore.orders;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * Server-side demo checkout: reserves stock, creates the order and compensates
 * on failure. Idempotency keys make client retries safe.
 *
 * This is still a demo workflow, not a production payment/saga implementation:
 * a crash between reserving stock and recording the order leaves stock reserved,
 * and such attempts are flagged for reconciliation instead of being retried.
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    private final CheckoutAttemptRepository attempts;
    private final OrdersRepository orders;
    private final InventoryClient inventory;

    public CheckoutController(CheckoutAttemptRepository attempts, OrdersRepository orders, InventoryClient inventory) {
        this.attempts = attempts;
        this.orders = orders;
        this.inventory = inventory;
    }

    @PostMapping
    public ResponseEntity<?> checkout(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody OrdersController.CreateOrder request
    ) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            return error(HttpStatus.BAD_REQUEST,
                "A valid Idempotency-Key header (8-64 letters, digits or . _ : -) is required");
        }
        String validationError = validate(request);
        if (validationError != null) {
            return error(HttpStatus.BAD_REQUEST, validationError);
        }

        Optional<CheckoutAttempt> existing = attempts.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get());
        }
        CheckoutAttempt attempt;
        try {
            attempt = attempts.saveAndFlush(
                new CheckoutAttempt(idempotencyKey, CheckoutAttempt.PENDING, 0, null));
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // Another request with the same key won the race.
            return error(HttpStatus.CONFLICT, "A checkout with this key is already being processed");
        }

        long bookId = request.bookId();
        int quantity = request.quantity();
        try {
            inventory.reserve(bookId, quantity);
        } catch (RestClientResponseException reserveError) {
            int status = reserveError.getStatusCode().value();
            if (status == 409 || status == 404) {
                // A definitive rejection: no stock was taken.
                String message = status == 409 ? "Insufficient stock" : "Book not found in inventory";
                attempts.save(attemptWith(attempt, CheckoutAttempt.FAILED, status, message));
                return error(HttpStatus.valueOf(status), message);
            }
            // A 5xx may follow a partially applied reservation: try to undo it.
            return compensate(attempt, bookId, quantity,
                "Inventory reservation failed; stock state is unconfirmed", reserveError);
        } catch (RuntimeException unavailable) {
            // The reservation may or may not have been applied: try to undo it.
            return compensate(attempt, bookId, quantity,
                "Inventory reservation failed; stock state is unconfirmed", unavailable);
        }

        OrderRecord order;
        try {
            order = orders.save(new OrderRecord(
                request.customerName().trim(), bookId, quantity, "CREATED"));
        } catch (RuntimeException saveError) {
            return compensate(attempt, bookId, quantity,
                "Order could not be saved; stock was released", saveError);
        }

        attempt.complete(order.getId());
        attempts.save(attempt);
        return ResponseEntity.status(HttpStatus.CREATED)
            .header("Idempotency-Replayed", "false")
            .body(order);
    }

    private ResponseEntity<?> compensate(CheckoutAttempt attempt, long bookId, int quantity,
            String message, RuntimeException cause) {
        try {
            inventory.release(bookId, quantity);
        } catch (RuntimeException releaseError) {
            attempts.save(attemptWith(attempt, CheckoutAttempt.NEEDS_RECONCILIATION, 502,
                "Stock release failed after a checkout error; reconcile inventory"));
            return error(HttpStatus.BAD_GATEWAY,
                "Checkout failed and stock compensation also failed; inventory needs reconciliation");
        }
        attempts.save(attemptWith(attempt, CheckoutAttempt.FAILED, 502, message));
        return error(HttpStatus.BAD_GATEWAY, message);
    }

    private ResponseEntity<?> replay(CheckoutAttempt attempt) {
        if (CheckoutAttempt.COMPLETED.equals(attempt.getStatus()) && attempt.getOrderId() != null) {
            return orders.findById(attempt.getOrderId())
                .<ResponseEntity<?>>map(order -> ResponseEntity.status(HttpStatus.CREATED)
                    .header("Idempotency-Replayed", "true")
                    .body(order))
                .orElseGet(() -> error(HttpStatus.CONFLICT, "The original order no longer exists"));
        }
        if (CheckoutAttempt.NEEDS_RECONCILIATION.equals(attempt.getStatus())) {
            return error(HttpStatus.CONFLICT,
                "A previous attempt needs reconciliation; check inventory before retrying");
        }
        if (CheckoutAttempt.PENDING.equals(attempt.getStatus())) {
            return error(HttpStatus.CONFLICT,
                "A checkout with this key is already in progress; retry after it finishes");
        }
        return error(HttpStatus.valueOf(attempt.getResponseStatus()),
            attempt.getMessage() == null ? "Checkout failed" : attempt.getMessage());
    }

    private static CheckoutAttempt attemptWith(CheckoutAttempt attempt, String status,
            int responseStatus, String message) {
        attempt.fail(status, responseStatus, message);
        return attempt;
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
