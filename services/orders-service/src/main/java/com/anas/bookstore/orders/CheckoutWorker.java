package com.anas.bookstore.orders;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    private final CatalogClient catalog;
    private final ObjectProvider<PaymentGateway> gateways;
    private final boolean enabled;
    private final int maxAttempts;
    private final boolean paymentsEnabled;
    private final String currency;

    public CheckoutWorker(OutboxRepository outbox, OrdersRepository orders,
                          CheckoutAttemptRepository attempts, InventoryClient inventory,
                          CatalogClient catalog, ObjectProvider<PaymentGateway> gateways,
                          @Value("${checkout.worker.enabled:true}") boolean enabled,
                          @Value("${checkout.worker.max-attempts:5}") int maxAttempts,
                          @Value("${payments.enabled:false}") boolean paymentsEnabled,
                          @Value("${payments.currency:INR}") String currency) {
        this.outbox = outbox;
        this.orders = orders;
        this.attempts = attempts;
        this.inventory = inventory;
        this.catalog = catalog;
        this.gateways = gateways;
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.paymentsEnabled = paymentsEnabled;
        this.currency = currency;
        if (paymentsEnabled && gateways.getIfAvailable() == null) {
            // Fail at startup rather than taking orders nobody can pay for.
            throw new IllegalStateException(
                "payments.enabled=true requires a PaymentGateway bean (PAYMENTS_ENABLED)");
        }
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
        if (OutboxEvent.RELEASE_STOCK.equals(event.getEventType())) {
            releaseStock(event);
            return;
        }
        // The reservation is handled separately: a failed payment or price lookup
        // must never be mistaken for an inventory rejection, because by then the
        // stock is already held.
        try {
            inventory.reserve(event.getBookId(), event.getQuantity(),
                event.getAggregateKey() + ":reserve");
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
            return;
        } catch (RuntimeException unavailable) {
            retryOrGiveUp(event, "Inventory unavailable: " + unavailable.getMessage());
            return;
        }

        if (!paymentsEnabled) {
            confirmOrder(event, "Stock reserved; order confirmed");
            event.done();
            outbox.save(event);
            return;
        }
        takePayment(event);
    }

    /**
     * Authorizes payment after the stock reservation. A decline is compensated by
     * cancelling the order and queueing a durable release; a provider error is
     * retried with the same idempotency key.
     */
    private void takePayment(OutboxEvent event) {
        PaymentGateway gateway = gateways.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException("payments.enabled=true but no PaymentGateway is configured");
        }
        long orderId = orderIdOf(event);
        PaymentGateway.PaymentResult result;
        try {
            long amountMinor = catalog.unitPriceMinor(event.getBookId()) * event.getQuantity();
            result = gateway.authorize(new PaymentGateway.PaymentRequest(
                event.getAggregateKey() + ":payment", orderId, event.getBookId(),
                event.getQuantity(), amountMinor, currency));
        } catch (RuntimeException providerError) {
            // Unknown outcome: retry with the same key rather than guess.
            retryOrGiveUp(event, "Payment step failed: " + describe(providerError));
            return;
        }
        if (result.status() == PaymentGateway.PaymentResult.Status.APPROVED) {
            confirmOrder(event, "Stock reserved; payment " + result.reference() + " approved");
            event.done();
            outbox.save(event);
            log.info("Checkout {} paid with {}", event.getAggregateKey(), result.reference());
            return;
        }
        // Payment declined: cancel, then release the reservation from the outbox so
        // the compensation retries until it succeeds.
        cancelOrder(event, 402, "Payment declined: " + result.message());
        outbox.save(new OutboxEvent(event.getAggregateKey(), OutboxEvent.RELEASE_STOCK,
            event.getBookId(), event.getQuantity()));
        event.done();
        outbox.save(event);
        log.info("Checkout {} declined ({}); stock release queued",
            event.getAggregateKey(), result.message());
    }

    /**
     * Compensating release. It is retried indefinitely because release carries an
     * idempotency key: a repeat returns the original result, so the worst case is
     * delay, never a double release.
     */
    private void releaseStock(OutboxEvent event) {
        try {
            inventory.release(event.getBookId(), event.getQuantity(),
                event.getAggregateKey() + ":release");
            event.done();
            outbox.save(event);
            log.info("Released stock for cancelled checkout {}", event.getAggregateKey());
        } catch (RuntimeException error) {
            event.retryLater("Release failed: " + describe(error));
            outbox.save(event);
            if (event.getAttempts() % 20 == 0) {
                log.warn("Stock release for {} still failing after {} attempts: {}",
                    event.getAggregateKey(), event.getAttempts(), describe(error));
            }
        }
    }

    private static String describe(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
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

    private void confirmOrder(OutboxEvent event, String message) {
        Optional<OrderRecord> order = orders.findById(orderIdOf(event));
        order.ifPresent(record -> {
            record.setStatus(OrderRecord.CONFIRMED);
            orders.save(record);
        });
        attempts.findById(event.getAggregateKey()).ifPresent(attempt -> {
            attempt.complete(202, message);
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
