package com.anas.bookstore.orders;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exposes the two numbers an operator actually needs to alert on:
 *
 * <ul>
 *   <li>{@code checkout_outbox_pending} - work the worker has not finished yet;</li>
 *   <li>{@code checkout_attempts_needs_reconciliation} - checkouts the reconciler
 *       has not settled yet.</li>
 * </ul>
 *
 * Both should return to zero on their own. A value that stays above zero means
 * the inventory service has been unreachable for longer than the retry budget,
 * which is exactly what the PrometheusRule in the chart alerts on.
 */
@Component
public class CheckoutMetrics {
    private final OutboxRepository outbox;
    private final CheckoutAttemptRepository attempts;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong reconciling = new AtomicLong();
    private final Counter confirmed;
    private final Counter cancelled;

    public CheckoutMetrics(MeterRegistry registry, OutboxRepository outbox,
                           CheckoutAttemptRepository attempts) {
        this.outbox = outbox;
        this.attempts = attempts;
        this.confirmed = Counter.builder("bookstore.checkout.confirmed")
            .description("Checkouts that ended CONFIRMED")
            .register(registry);
        this.cancelled = Counter.builder("bookstore.checkout.cancelled")
            .description("Checkouts that ended CANCELLED")
            .register(registry);
        Gauge.builder("checkout.outbox.pending", pending, AtomicLong::get)
            .description("Checkout outbox events still waiting for the worker")
            .register(registry);
        Gauge.builder("checkout.attempts.needs_reconciliation", reconciling, AtomicLong::get)
            .description("Checkouts the reconciler has not settled yet")
            .register(registry);
    }

    /** Counted once per settled order, so the checkout success SLI is honest. */
    public void checkoutConfirmed() {
        confirmed.increment();
    }

    public void checkoutCancelled() {
        cancelled.increment();
    }

    @Scheduled(fixedDelayString = "${checkout.metrics.delay-ms:15000}",
               initialDelayString = "${checkout.metrics.initial-delay-ms:20000}")
    public void refresh() {
        try {
            pending.set(outbox.countByStatus(OutboxEvent.PENDING));
            reconciling.set(attempts.countByStatus(CheckoutAttempt.NEEDS_RECONCILIATION));
        } catch (RuntimeException unavailable) {
            // Metrics must never break a healthy service; the next cycle retries.
            pending.set(-1);
            reconciling.set(-1);
        }
    }
}
