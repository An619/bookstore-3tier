package com.anas.bookstore.orders;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles checkouts the worker could not finish, without a human in the loop.
 *
 * <p>When {@link CheckoutWorker} exhausts its retries the checkout is left in
 * {@link CheckoutAttempt#NEEDS_RECONCILIATION}: the worker does not know whether
 * the reservation landed and the response was lost, or never landed at all.
 * Guessing either way is wrong - confirming could overbook stock, cancelling
 * would silently drop a paid order. Instead this reconciler asks the inventory
 * service what it actually recorded for the checkout's idempotency key:
 *
 * <ul>
 *   <li>the reservation is in the inventory ledger -&gt; the order is confirmed;</li>
 *   <li>the ledger has no entry -&gt; nothing was reserved, so the event is safely
 *       re-queued for the worker (bounded by {@code max-cycles});</li>
 *   <li>still nothing after the final cycle -&gt; the order is cancelled, which is
 *       safe precisely because the ledger proves no stock is held.</li>
 * </ul>
 *
 * <p>Every decision is derived from the inventory ledger, so a crash before the
 * database commit simply repeats the same read on the next cycle.
 */
@Component
public class CheckoutReconciler {
    private static final Logger log = LoggerFactory.getLogger(CheckoutReconciler.class);

    static final String RESERVATION_PREFIX = ":reserve";
    private static final String RESERVE_OPERATION = "RESERVE";

    private final CheckoutAttemptRepository attempts;
    private final OutboxRepository outbox;
    private final OrdersRepository orders;
    private final InventoryClient inventory;
    private final ObjectProvider<PaymentGateway> gateways;
    private final boolean enabled;
    private final int maxCycles;
    private final boolean paymentsEnabled;

    public CheckoutReconciler(CheckoutAttemptRepository attempts, OutboxRepository outbox,
                              OrdersRepository orders, InventoryClient inventory,
                              ObjectProvider<PaymentGateway> gateways,
                              @Value("${checkout.reconciler.enabled:true}") boolean enabled,
                              @Value("${checkout.reconciler.max-cycles:3}") int maxCycles,
                              @Value("${payments.enabled:false}") boolean paymentsEnabled) {
        this.attempts = attempts;
        this.outbox = outbox;
        this.orders = orders;
        this.inventory = inventory;
        this.gateways = gateways;
        this.enabled = enabled;
        this.maxCycles = maxCycles;
        this.paymentsEnabled = paymentsEnabled;
    }

    @Scheduled(fixedDelayString = "${checkout.reconciler.delay-ms:5000}",
               initialDelayString = "${checkout.reconciler.initial-delay-ms:8000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            reconcilePending();
        } catch (RuntimeException error) {
            // A broken cycle must not kill the scheduler thread.
            log.warn("Checkout reconciliation cycle failed: {}", error.toString());
        }
    }

    /** Settles one batch; public so tests can drive it deterministically. */
    public int reconcilePending() {
        int settled = 0;
        for (CheckoutAttempt attempt : attempts.findTop20ByStatusOrderByIdempotencyKeyAsc(
                CheckoutAttempt.NEEDS_RECONCILIATION)) {
            settle(attempt);
            settled++;
        }
        return settled;
    }

    private void settle(CheckoutAttempt attempt) {
        Optional<OutboxEvent> event = eventFor(attempt);
        if (event.isEmpty()) {
            fail(attempt, null, "No outbox event to reconcile");
            return;
        }
        if (paymentsEnabled) {
            // With money involved, the payment record decides; the stock ledger
            // alone must never confirm an order.
            settlePayment(attempt, event.get());
            return;
        }
        Optional<InventoryClient.InventoryOperationRecord> recorded;
        try {
            recorded = inventory.findOperation(attempt.getIdempotencyKey() + RESERVATION_PREFIX);
        } catch (RuntimeException unavailable) {
            // Inventory is still unreachable: stay in NEEDS_RECONCILIATION and retry.
            log.info("Reconciliation deferred for {}: {}", attempt.getIdempotencyKey(), unavailable.getMessage());
            return;
        }
        if (recorded.isPresent()) {
            if (matches(recorded.get(), event.get())) {
                confirm(attempt, event.get());
            } else {
                // The key was reused for a different payload; never guess.
                fail(attempt, event.get(), "Inventory recorded a different operation for this checkout");
            }
            return;
        }
        requeueOrCancel(attempt, event.get());
    }

    /**
     * Settles an unresolved checkout from the payment record: captured means the
     * order is real (stock was reserved before payment), declined means cancel and
     * release, and no record means nothing was charged so a retry is safe.
     */
    private void settlePayment(CheckoutAttempt attempt, OutboxEvent event) {
        PaymentGateway gateway = gateways.getIfAvailable();
        if (gateway == null) {
            log.warn("payments.enabled=true but no PaymentGateway is configured; deferring {}",
                attempt.getIdempotencyKey());
            return;
        }
        Optional<PaymentGateway.PaymentResult> payment;
        try {
            payment = gateway.find(attempt.getIdempotencyKey() + ":payment");
        } catch (RuntimeException unavailable) {
            log.info("Payment lookup deferred for {}: {}", attempt.getIdempotencyKey(),
                unavailable.getMessage());
            return;
        }
        if (payment.isEmpty()) {
            // Nothing was charged, so retrying the checkout is safe.
            requeueOrCancel(attempt, event);
            return;
        }
        if (payment.get().status() == PaymentGateway.PaymentResult.Status.APPROVED) {
            // Payment captured and stock reserved: the order is real.
            confirm(attempt, event);
            return;
        }
        // Charged nothing, declined: cancel and hand the stock back.
        cancelAndRelease(attempt, event, "Payment declined: " + payment.get().message());
    }

    private void cancelAndRelease(CheckoutAttempt attempt, OutboxEvent event, String reason) {
        fail(attempt, event, reason);
        boolean alreadyQueued = outbox.findByAggregateKey(attempt.getIdempotencyKey()).stream()
            .anyMatch(queued -> OutboxEvent.RELEASE_STOCK.equals(queued.getEventType()));
        if (!alreadyQueued) {
            outbox.save(new OutboxEvent(attempt.getIdempotencyKey(), OutboxEvent.RELEASE_STOCK,
                event.getBookId(), event.getQuantity()));
        }
    }

    private void requeueOrCancel(CheckoutAttempt attempt, OutboxEvent event) {
        if (event.getReconcileCycles() >= maxCycles) {
            fail(attempt, event, "Inventory never confirmed the reservation; checkout cancelled");
            return;
        }
        event.requeue();
        outbox.save(event);
        attempt.retryAfterReconciliation("Reservation not recorded by inventory; retrying");
        attempts.save(attempt);
        log.info("Re-queued checkout {} after reconciliation cycle {}",
            attempt.getIdempotencyKey(), event.getReconcileCycles());
    }

    private void confirm(CheckoutAttempt attempt, OutboxEvent event) {
        order(attempt).ifPresent(record -> {
            record.setStatus(OrderRecord.CONFIRMED);
            orders.save(record);
        });
        attempt.complete(202, "Reservation confirmed during reconciliation");
        attempts.save(attempt);
        event.done();
        outbox.save(event);
        log.info("Reconciliation confirmed checkout {}", attempt.getIdempotencyKey());
    }

    private void fail(CheckoutAttempt attempt, OutboxEvent event, String reason) {
        order(attempt).ifPresent(record -> {
            record.setStatus(OrderRecord.CANCELLED);
            orders.save(record);
        });
        attempt.fail(502, reason);
        attempts.save(attempt);
        if (event != null) {
            event.failed(reason);
            outbox.save(event);
        }
        log.warn("Reconciliation cancelled checkout {}: {}", attempt.getIdempotencyKey(), reason);
    }

    /** True when the ledger entry is the reservation this checkout asked for. */
    private static boolean matches(InventoryClient.InventoryOperationRecord record, OutboxEvent event) {
        return RESERVE_OPERATION.equals(record.operation())
            && record.bookId() != null && record.bookId().equals(event.getBookId())
            && record.quantity() != null && record.quantity().equals(event.getQuantity());
    }

    private Optional<OrderRecord> order(CheckoutAttempt attempt) {
        if (attempt.getOrderId() == null) {
            return Optional.empty();
        }
        return orders.findById(attempt.getOrderId());
    }

    private Optional<OutboxEvent> eventFor(CheckoutAttempt attempt) {
        return outbox.findByAggregateKey(attempt.getIdempotencyKey()).stream()
            .filter(event -> OutboxEvent.RESERVE_STOCK.equals(event.getEventType()))
            .findFirst();
    }
}
