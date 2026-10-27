package com.anas.bookstore.orders;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Drains the checkout outbox: reserves stock for each pending event and records
 * the outcome on the order and the idempotency record.
 *
 * Delivery is at-least-once. It is safe because the inventory call carries an
 * idempotency key derived from the checkout key, so a repeat returns the original
 * result instead of reserving stock twice. A crash at any point leaves the event
 * PENDING, and the next cycle re-runs it.
 */
@Component
public class CheckoutWorker {
    private static final Logger log = LoggerFactory.getLogger(CheckoutWorker.class);

    private final OutboxRepository outbox;
    private final OrdersRepository orders;
    private final CheckoutAttemptRepository attempts;
    private final InventoryClient inventory;
    private final boolean enabled;
    private final int maxAttempts;

    public CheckoutWorker(OutboxRepository outbox, OrdersRepository orders,
                          CheckoutAttemptRepository attempts, InventoryClient inventory,
                          @Value("${checkout.worker.enabled:true}") boolean enabled,
                          @Value("${checkout.worker.max-attempts:5}") int maxAttempts) {
        this.outbox = outbox;
        this.orders = orders;
        this.attempts = attempts;
        this.inventory = inventory;
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${checkout.worker.delay-ms:2000}",
               initialDelayString = "${checkout.worker.initial-delay-ms:3000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            processPending();
        } catch (RuntimeException error) {
            // Never let a scheduling exception kill the worker thread.
            log.warn("Checkout outbox cycle failed: {}", error.toString());
        }
    }

    /** Processes one batch; public so tests can drive it deterministically. */
    public int processPending() {
        List<OutboxEvent> batch = outbox.findTop20ByStatusOrderByIdAsc(OutboxEvent.PENDING);
        int processed = 0;
        for (OutboxEvent event : batch) {
            process(event);
            processed++;
        }
        return processed;
    }

    private void process(OutboxEvent event) {
        // Count the attempt before calling out, so a crash cannot retry forever.
        event.incrementAttempts();
        try {
            inventory.reserve(event.getBookId(), event.getQuantity(),
                event.getAggregateKey() + ":reserve");
            confirmOrder(event);
            event.done();
            outbox.save(event);
            log.info("Reserved stock for checkout {}", event.getAggregateKey());
        } catch (RestClientResponseException rejection) {
            int status = rejection.getStatusCode().value();
            if (status == 404 || status == 409 || status == 400) {
                // Definitive rejection: no stock was taken.
                String reason = status == 409 ? "Insufficient stock"
                    : status == 404 ? "Book not found in inventory"
                    : "Inventory rejected the reservation";
                cancelOrder(event, status, reason);
                event.done();
                outbox.save(event);
                log.info("Checkout {} rejected ({})", event.getAggregateKey(), reason);
                return;
            }
            retryOrGiveUp(event, "Inventory error " + status);
        } catch (RuntimeException unavailable) {
            retryOrGiveUp(event, "Inventory unavailable: " + unavailable.getMessage());
        }
    }

    private void retryOrGiveUp(OutboxEvent event, String error) {
        if (event.getAttempts() >= maxAttempts) {
            event.failed(error);
            outbox.save(event);
            attempts.findById(event.getAggregateKey()).ifPresent(attempt -> {
                attempt.needsReconciliation(502,
                    "Stock reservation could not be confirmed; reconcile inventory");
                attempts.save(attempt);
            });
            log.warn("Checkout {} needs reconciliation after {} attempts",
                event.getAggregateKey(), event.getAttempts());
            return;
        }
        event.retryLater(error);
        outbox.save(event);
    }

    private void confirmOrder(OutboxEvent event) {
        Optional<OrderRecord> order = orders.findById(orderIdOf(event));
        order.ifPresent(record -> {
            record.setStatus(OrderRecord.CONFIRMED);
            orders.save(record);
        });
        attempts.findById(event.getAggregateKey()).ifPresent(attempt -> {
            attempt.complete(202, "Stock reserved; order confirmed");
            attempts.save(attempt);
        });
    }

    private void cancelOrder(OutboxEvent event, int status, String reason) {
        Optional<OrderRecord> order = orders.findById(orderIdOf(event));
        order.ifPresent(record -> {
            record.setStatus(OrderRecord.CANCELLED);
            orders.save(record);
        });
        attempts.findById(event.getAggregateKey()).ifPresent(attempt -> {
            attempt.fail(status, reason);
            attempts.save(attempt);
        });
    }

    private long orderIdOf(OutboxEvent event) {
        return attempts.findById(event.getAggregateKey())
            .map(CheckoutAttempt::getOrderId)
            .orElseThrow(() -> new IllegalStateException(
                "No checkout attempt for outbox event " + event.getAggregateKey()));
    }
}
