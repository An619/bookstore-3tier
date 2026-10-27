package com.anas.bookstore.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The worker gives up after {@code checkout.worker.max-attempts}; the reconciler
 * then settles the checkout from the inventory ledger with no human involved.
 * Errors are injected through the stub, so these tests never sleep.
 */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class CheckoutReconcilerTest {
    private static final String BODY =
        "{\"customerName\":\"Reconcile Customer\",\"bookId\":1,\"quantity\":2}";
    private static final int WORKER_ATTEMPTS = 3;

    static final InventoryStub INVENTORY = InventoryStub.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inventory.service.url", INVENTORY::baseUrl);
        registry.add("spring.datasource.url",
            () -> "jdbc:h2:mem:orders-reconcile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Autowired MockMvc mvc;
    @Autowired OrdersRepository orders;
    @Autowired CheckoutAttemptRepository attempts;
    @Autowired OutboxRepository outbox;
    @Autowired CheckoutWorker worker;
    @Autowired CheckoutReconciler reconciler;

    @BeforeEach
    void reset() {
        INVENTORY.reset();
        attempts.deleteAll();
        outbox.deleteAll();
    }

    /** Runs one worker batch per queued 500, so every attempt fails. */
    private void failTheWorker() {
        for (int attempt = 0; attempt < WORKER_ATTEMPTS; attempt++) {
            INVENTORY.respondWith(500);
            worker.processPending();
        }
    }

    /** Submits a checkout and exhausts the worker's retries against failing inventory. */
    private long exhaustedCheckout(String key) throws Exception {
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(BODY))
            .andExpect(status().isAccepted());
        failTheWorker();
        CheckoutAttempt attempt = attempts.findById(key).orElseThrow();
        assertEquals(CheckoutAttempt.NEEDS_RECONCILIATION, attempt.getStatus(),
            "the worker must hand the checkout to the reconciler");
        return attempt.getOrderId();
    }

    private String statusOf(long orderId) {
        return orders.findById(orderId).orElseThrow().getStatus();
    }

    private OutboxEvent eventFor(String key) {
        return outbox.findByAggregateKey(key).get(0);
    }

    @Test
    void reservationThatLandedIsConfirmedWithoutReservingAgain() throws Exception {
        String key = "reconcile-key-0001";
        long orderId = exhaustedCheckout(key);
        // Inventory committed the reservation but the reply never reached orders.
        INVENTORY.recordReservation(key + ":reserve", 1, 2);
        int callsBefore = INVENTORY.calls().size();

        assertEquals(1, reconciler.reconcilePending());

        assertEquals(OrderRecord.CONFIRMED, statusOf(orderId));
        assertEquals(CheckoutAttempt.COMPLETED, attempts.findById(key).orElseThrow().getStatus());
        assertEquals(OutboxEvent.DONE, eventFor(key).getStatus());
        // Settling is a read: no second reserve call was made.
        assertEquals(callsBefore + 1, INVENTORY.calls().size());
        assertTrue(INVENTORY.calls().get(callsBefore).startsWith("GET /api/inventory/operations/"));
    }

    @Test
    void missingReservationIsRequeuedAndThenConfirmed() throws Exception {
        String key = "reconcile-key-0002";
        long orderId = exhaustedCheckout(key);

        assertEquals(1, reconciler.reconcilePending());

        // Nothing is in the ledger, so retrying is safe: the event is back in the queue.
        assertEquals(OrderRecord.PENDING, statusOf(orderId));
        assertEquals(CheckoutAttempt.PENDING, attempts.findById(key).orElseThrow().getStatus());
        assertEquals(OutboxEvent.PENDING, eventFor(key).getStatus());
        assertEquals(1, eventFor(key).getReconcileCycles().intValue());

        // Inventory recovered: the retried event reserves and confirms exactly once.
        assertEquals(1, worker.processPending());
        assertEquals(OrderRecord.CONFIRMED, statusOf(orderId));
        assertEquals(CheckoutAttempt.COMPLETED, attempts.findById(key).orElseThrow().getStatus());
        assertEquals(OutboxEvent.DONE, eventFor(key).getStatus());
    }

    @Test
    void reservationThatNeverLandsIsCancelledAfterTheFinalCycle() throws Exception {
        String key = "reconcile-key-0003";
        long orderId = exhaustedCheckout(key);

        // Each of the allowed cycles (checkout.reconciler.max-cycles=2) re-queues
        // the checkout and the worker fails again.
        for (int cycle = 1; cycle <= 2; cycle++) {
            assertEquals(1, reconciler.reconcilePending());
            assertEquals(cycle, eventFor(key).getReconcileCycles().intValue());
            assertEquals(OutboxEvent.PENDING, eventFor(key).getStatus());
            assertEquals(CheckoutAttempt.PENDING, attempts.findById(key).orElseThrow().getStatus());
            failTheWorker();
            assertEquals(CheckoutAttempt.NEEDS_RECONCILIATION,
                attempts.findById(key).orElseThrow().getStatus());
        }

        // The final cycle: the ledger still proves no stock is held, so
        // cancelling is safe and needs nobody.
        assertEquals(1, reconciler.reconcilePending());

        assertEquals(OrderRecord.CANCELLED, statusOf(orderId));
        CheckoutAttempt attempt = attempts.findById(key).orElseThrow();
        assertEquals(CheckoutAttempt.FAILED, attempt.getStatus());
        assertEquals(502, attempt.getResponseStatus().intValue());
        assertEquals(OutboxEvent.FAILED, eventFor(key).getStatus());
        // The counter stopped at the limit; no further retry was queued.
        assertEquals(2, eventFor(key).getReconcileCycles().intValue());
    }

    @Test
    void unavailableLedgerDefersInsteadOfGuessing() throws Exception {
        String key = "reconcile-key-0004";
        long orderId = exhaustedCheckout(key);
        INVENTORY.failLedgerWith(503);

        assertEquals(1, reconciler.reconcilePending());

        // Unchanged: no order, attempt or outbox mutation while inventory is unknown.
        assertEquals(OrderRecord.PENDING, statusOf(orderId));
        assertEquals(CheckoutAttempt.NEEDS_RECONCILIATION, attempts.findById(key).orElseThrow().getStatus());
        assertEquals(OutboxEvent.FAILED, eventFor(key).getStatus());
        assertEquals(0, eventFor(key).getReconcileCycles().intValue());
    }

    @Test
    void reusedKeyIsNeverSilentlyConfirmed() throws Exception {
        String key = "reconcile-key-0005";
        long orderId = exhaustedCheckout(key);
        // Same key, different quantity: the ledger proves it is not our reservation.
        INVENTORY.recordOperation(key + ":reserve", "RESERVE", 1, 99);

        reconciler.reconcilePending();

        assertEquals(OrderRecord.CANCELLED, statusOf(orderId));
        CheckoutAttempt attempt = attempts.findById(key).orElseThrow();
        assertEquals(CheckoutAttempt.FAILED, attempt.getStatus());
        assertEquals("Inventory recorded a different operation for this checkout", attempt.getMessage());
    }

    @Test
    void nothingToReconcileIsANoOp() {
        assertEquals(0, reconciler.reconcilePending());
        assertTrue(INVENTORY.calls().isEmpty());
        assertEquals(0, outbox.count());
        assertEquals(0, attempts.count());
    }
}
