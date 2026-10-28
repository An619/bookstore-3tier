package com.anas.bookstore.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Checkout is asynchronous: the request records the order + outbox event and
 * returns 202, then {@link CheckoutWorker} reserves stock and confirms or
 * cancels the order. These tests drive the worker explicitly
 * (checkout.worker.enabled=false in application-test.properties).
 */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class CheckoutControllerTest {
    private static final String BODY =
        "{\"customerName\":\"Checkout Customer\",\"bookId\":1,\"quantity\":2}";

    static final InventoryStub INVENTORY = InventoryStub.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inventory.service.url", INVENTORY::baseUrl);
        // Each test class owns a database: a second Spring context must not drop
        // the schema another context is still using.
        registry.add("spring.datasource.url",
            () -> "jdbc:h2:mem:orders-checkout;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Autowired MockMvc mvc;
    @Autowired OrdersRepository orders;
    @Autowired CheckoutAttemptRepository attempts;
    @Autowired OutboxRepository outbox;
    @Autowired CheckoutWorker worker;

    private long baselineOrders;

    @BeforeEach
    void reset() {
        INVENTORY.reset();
        // Other test classes share this in-memory database and its seed row.
        attempts.deleteAll();
        outbox.deleteAll();
        baselineOrders = orders.count();
    }

    private ResultActions checkout(String key) throws Exception {
        var request = post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private long orderIdFor(String key) {
        return attempts.findById(key).orElseThrow().getOrderId();
    }

    private String statusOf(long orderId) {
        return orders.findById(orderId).orElseThrow().getStatus();
    }

    @Test
    void acceptsAndQueuesTheReservation() throws Exception {
        checkout("checkout-key-0001")
            .andExpect(status().isAccepted())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.status").value(OrderRecord.PENDING));

        assertEquals(baselineOrders + 1, orders.count());
        assertEquals(OrderRecord.PENDING, statusOf(orderIdFor("checkout-key-0001")));
        assertEquals(1, outbox.findByAggregateKey("checkout-key-0001").size());
        assertEquals(OutboxEvent.PENDING,
            outbox.findByAggregateKey("checkout-key-0001").get(0).getStatus());
        // Nothing has been reserved yet: the worker has not run.
        assertTrue(INVENTORY.calls().isEmpty(), "checkout must not call inventory inline");
    }

    @Test
    void workerConfirmsTheOrderAndReservesStockOnce() throws Exception {
        checkout("checkout-key-0002").andExpect(status().isAccepted());
        long orderId = orderIdFor("checkout-key-0002");

        assertEquals(1, worker.processPending());

        assertEquals(OrderRecord.CONFIRMED, statusOf(orderId));
        assertEquals(CheckoutAttempt.COMPLETED, attempts.findById("checkout-key-0002").orElseThrow().getStatus());
        assertEquals(OutboxEvent.DONE, outbox.findByAggregateKey("checkout-key-0002").get(0).getStatus());
        assertEquals(List.of("POST /api/inventory/1/reserve?quantity=2"), INVENTORY.calls());
        assertEquals("checkout-key-0002:reserve", INVENTORY.idempotencyKeys().get(0));
    }

    @Test
    void replayReturnsTheSameOrderWithoutReservingAgain() throws Exception {
        checkout("checkout-key-0003").andExpect(status().isAccepted());
        worker.processPending();
        long orderId = orderIdFor("checkout-key-0003");
        int callsAfterFirst = INVENTORY.calls().size();

        checkout("checkout-key-0003")
            .andExpect(status().isAccepted())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.id").value(orderId))
            .andExpect(jsonPath("$.status").value(OrderRecord.CONFIRMED));

        assertEquals(baselineOrders + 1, orders.count());
        assertEquals(1, outbox.findByAggregateKey("checkout-key-0003").size());
        assertEquals(callsAfterFirst, INVENTORY.calls().size());
    }

    @Test
    void insufficientStockCancelsTheOrder() throws Exception {
        INVENTORY.respondWith(409);
        checkout("checkout-key-0004").andExpect(status().isAccepted());
        long orderId = orderIdFor("checkout-key-0004");

        worker.processPending();

        assertEquals(OrderRecord.CANCELLED, statusOf(orderId));
        CheckoutAttempt attempt = attempts.findById("checkout-key-0004").orElseThrow();
        assertEquals(CheckoutAttempt.FAILED, attempt.getStatus());
        assertEquals(409, attempt.getResponseStatus().intValue());
        assertEquals("Insufficient stock", attempt.getMessage());
        assertEquals(OutboxEvent.DONE, outbox.findByAggregateKey("checkout-key-0004").get(0).getStatus());

        // A replay of a failed checkout keeps the original outcome and creates nothing.
        checkout("checkout-key-0004").andExpect(status().isConflict());
        assertEquals(baselineOrders + 1, orders.count());
    }

    @Test
    void transientFailuresAreRetriedUntilTheySucceed() throws Exception {
        INVENTORY.respondWith(500);
        INVENTORY.respondWith(500);
        checkout("checkout-key-0005").andExpect(status().isAccepted());
        long orderId = orderIdFor("checkout-key-0005");

        worker.processPending();
        assertEquals(OrderRecord.PENDING, statusOf(orderId));
        assertEquals(OutboxEvent.PENDING, outbox.findByAggregateKey("checkout-key-0005").get(0).getStatus());

        worker.processPending();
        assertEquals(OrderRecord.PENDING, statusOf(orderId));

        worker.processPending();
        assertEquals(OrderRecord.CONFIRMED, statusOf(orderId));
        assertEquals(3, INVENTORY.calls().size());
        // Every retry reused the same key, so inventory can deduplicate.
        assertEquals(1, INVENTORY.idempotencyKeys().stream().distinct().count());
    }

    @Test
    void exhaustedRetriesNeedReconciliationAndBlockReplay() throws Exception {
        INVENTORY.respondWith(500);
        INVENTORY.respondWith(500);
        INVENTORY.respondWith(500);
        checkout("checkout-key-0006").andExpect(status().isAccepted());

        worker.processPending();
        worker.processPending();
        worker.processPending();

        assertEquals(CheckoutAttempt.NEEDS_RECONCILIATION,
            attempts.findById("checkout-key-0006").orElseThrow().getStatus());
        assertEquals(OutboxEvent.FAILED, outbox.findByAggregateKey("checkout-key-0006").get(0).getStatus());

        checkout("checkout-key-0006")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(
                "A previous attempt is being reconciled automatically; retry shortly"));
    }

    @Test
    void unknownBookCancelsTheOrder() throws Exception {
        INVENTORY.respondWith(404);
        checkout("checkout-key-0007").andExpect(status().isAccepted());
        long orderId = orderIdFor("checkout-key-0007");

        worker.processPending();

        assertEquals(OrderRecord.CANCELLED, statusOf(orderId));
        assertEquals(404, attempts.findById("checkout-key-0007").orElseThrow().getResponseStatus().intValue());
    }

    @Test
    void rejectsMissingOrMalformedIdempotencyKey() throws Exception {
        checkout(null).andExpect(status().isBadRequest());
        checkout("short").andExpect(status().isBadRequest());
        checkout("contains a space").andExpect(status().isBadRequest());
        // Longer than 48 characters: the worker must be able to append ":reserve".
        checkout("k".repeat(49)).andExpect(status().isBadRequest());
        assertTrue(INVENTORY.calls().isEmpty());
        assertEquals(baselineOrders, orders.count());
    }

    @Test
    void rejectsInvalidBodyWithoutQueueingWork() throws Exception {
        mvc.perform(post("/api/checkout")
                .header("Idempotency-Key", "checkout-key-0008")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"\",\"bookId\":0,\"quantity\":0}"))
            .andExpect(status().isBadRequest());
        assertTrue(outbox.findByAggregateKey("checkout-key-0008").isEmpty());
        assertEquals(baselineOrders, orders.count());
    }

    @Test
    void workerWithNothingPendingIsANoOp() {
        assertEquals(0, worker.processPending());
        assertNotEquals(0, baselineOrders);
        assertTrue(INVENTORY.calls().isEmpty());
    }
}
