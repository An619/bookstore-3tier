package com.anas.bookstore.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The payment step of the checkout saga.
 *
 * <p>Stock is reserved first, then payment is authorized. A decline must never
 * leave stock held, so the compensation is a durable outbox event rather than an
 * inline call, and a provider that cannot be reached must not confirm anything.
 */
@SpringBootTest(properties = {
    "payments.enabled=true",
    "payments.currency=INR",
    "spring.datasource.url=jdbc:h2:mem:orders-payments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "checkout.worker.enabled=false",
    "checkout.reconciler.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckoutPaymentTest {
    private static final String BODY =
        "{\"customerName\":\"Paying Customer\",\"bookId\":1,\"quantity\":2}";

    static final InventoryStub INVENTORY = InventoryStub.start();
    static final CatalogStub CATALOG = CatalogStub.start();
    static final StubPayments PAYMENTS = new StubPayments();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("inventory.service.url", INVENTORY::baseUrl);
        registry.add("catalog.service.url", CATALOG::baseUrl);
    }

    @TestConfiguration
    static class PaymentBeans {
        @Bean
        PaymentGateway paymentGateway() {
            return PAYMENTS;
        }
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
        CATALOG.reset();
        PAYMENTS.reset();
        attempts.deleteAll();
        outbox.deleteAll();
    }

    private void checkout(String key) throws Exception {
        mvc.perform(post("/api/checkout").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(BODY))
            .andExpect(status().isAccepted());
    }

    private long orderIdFor(String key) {
        return attempts.findById(key).orElseThrow().getOrderId();
    }

    private String statusOf(long orderId) {
        return orders.findById(orderId).orElseThrow().getStatus();
    }

    private OutboxEvent eventOfType(String key, String type) {
        return outbox.findByAggregateKey(key).stream()
            .filter(event -> type.equals(event.getEventType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + type + " event for " + key));
    }

    @Test
    void approvedPaymentConfirmsTheOrderWithAServerSideAmount() throws Exception {
        PAYMENTS.respondWith(PaymentGateway.PaymentResult.approved("pay_123"));
        checkout("payment-key-0001");

        assertEquals(1, worker.processPending());

        long orderId = orderIdFor("payment-key-0001");
        assertEquals(OrderRecord.CONFIRMED, statusOf(orderId));
        CheckoutAttempt attempt = attempts.findById("payment-key-0001").orElseThrow();
        assertEquals(CheckoutAttempt.COMPLETED, attempt.getStatus());
        assertTrue(attempt.getMessage().contains("pay_123"), attempt.getMessage());

        PaymentGateway.PaymentRequest request = PAYMENTS.requests().get(0);
        assertEquals("payment-key-0001:payment", request.idempotencyKey());
        assertEquals(1L, request.bookId());
        assertEquals(2, request.quantity());
        // 599.00 per unit from the catalog, two units, in paise.
        assertEquals(119800L, request.amountMinor());
        assertEquals("INR", request.currency());
        assertEquals(1, CATALOG.calls().size(), "the price is read exactly once");
    }

    @Test
    void declinedPaymentCancelsTheOrderAndQueuesADurableRelease() throws Exception {
        PAYMENTS.respondWith(PaymentGateway.PaymentResult.declined("card declined"));
        checkout("payment-key-0002");
        long orderId = orderIdFor("payment-key-0002");

        worker.processPending();

        assertEquals(OrderRecord.CANCELLED, statusOf(orderId));
        CheckoutAttempt attempt = attempts.findById("payment-key-0002").orElseThrow();
        assertEquals(CheckoutAttempt.FAILED, attempt.getStatus());
        assertEquals(402, attempt.getResponseStatus().intValue());
        assertEquals(OutboxEvent.DONE, eventOfType("payment-key-0002", OutboxEvent.RESERVE_STOCK).getStatus());
        OutboxEvent release = eventOfType("payment-key-0002", OutboxEvent.RELEASE_STOCK);
        assertEquals(OutboxEvent.PENDING, release.getStatus(),
            "the release must survive a crash instead of being an inline call");

        // The worker then compensates the stock reservation.
        assertEquals(1, worker.processPending());
        assertEquals(OutboxEvent.DONE, eventOfType("payment-key-0002", OutboxEvent.RELEASE_STOCK).getStatus());
        assertTrue(INVENTORY.calls().contains("POST /api/inventory/1/release?quantity=2"),
            INVENTORY.calls().toString());
        assertTrue(INVENTORY.idempotencyKeys().contains("payment-key-0002:release"));
    }

    @Test
    void providerErrorsAreRetriedWithTheSameKey() throws Exception {
        PAYMENTS.failNext(2);
        checkout("payment-key-0003");
        long orderId = orderIdFor("payment-key-0003");

        worker.processPending();
        assertEquals(OrderRecord.PENDING, statusOf(orderId));
        assertEquals(OutboxEvent.PENDING, eventOfType("payment-key-0003", OutboxEvent.RESERVE_STOCK).getStatus());

        worker.processPending();
        PAYMENTS.respondWith(PaymentGateway.PaymentResult.approved("pay_retry"));
        worker.processPending();

        assertEquals(OrderRecord.CONFIRMED, statusOf(orderId));
        List<String> keys = PAYMENTS.requests().stream()
            .map(PaymentGateway.PaymentRequest::idempotencyKey).distinct().toList();
        assertEquals(List.of("payment-key-0003:payment"), keys, "retries must reuse the key");
    }

    @Test
    void exhaustedProviderErrorsNeedReconciliationAndACapturedPaymentConfirms() throws Exception {
        PAYMENTS.failNext(3);
        checkout("payment-key-0004");
        long orderId = orderIdFor("payment-key-0004");
        worker.processPending();
        worker.processPending();
        worker.processPending();
        assertEquals(CheckoutAttempt.NEEDS_RECONCILIATION,
            attempts.findById("payment-key-0004").orElseThrow().getStatus());

        // The PSP did capture it; the reconciler asks the provider, not inventory.
        PAYMENTS.recordPayment("payment-key-0004:payment",
            PaymentGateway.PaymentResult.approved("pay_late"));
        assertEquals(1, reconciler.reconcilePending());

        assertEquals(OrderRecord.CONFIRMED, statusOf(orderId));
        assertEquals(CheckoutAttempt.COMPLETED, attempts.findById("payment-key-0004").orElseThrow().getStatus());
    }

    @Test
    void declinedPaymentFoundDuringReconciliationCancelsAndReleases() throws Exception {
        PAYMENTS.failNext(3);
        checkout("payment-key-0005");
        long orderId = orderIdFor("payment-key-0005");
        worker.processPending();
        worker.processPending();
        worker.processPending();

        PAYMENTS.recordPayment("payment-key-0005:payment",
            PaymentGateway.PaymentResult.declined("insufficient funds"));
        assertEquals(1, reconciler.reconcilePending());

        assertEquals(OrderRecord.CANCELLED, statusOf(orderId));
        assertEquals(OutboxEvent.PENDING, eventOfType("payment-key-0005", OutboxEvent.RELEASE_STOCK).getStatus());
        // Reconciliation is idempotent: a second pass must not queue another release.
        assertEquals(1, outbox.findByAggregateKey("payment-key-0005").stream()
            .filter(event -> OutboxEvent.RELEASE_STOCK.equals(event.getEventType())).count());
    }

    @Test
    void unknownPaymentIsRetriedRatherThanConfirmedOrCancelled() throws Exception {
        PAYMENTS.failNext(3);
        checkout("payment-key-0006");
        long orderId = orderIdFor("payment-key-0006");
        worker.processPending();
        worker.processPending();
        worker.processPending();

        assertEquals(1, reconciler.reconcilePending());

        assertEquals(OrderRecord.PENDING, statusOf(orderId));
        assertEquals(CheckoutAttempt.PENDING, attempts.findById("payment-key-0006").orElseThrow().getStatus());
        assertTrue(outbox.findByAggregateKey("payment-key-0006").stream()
            .noneMatch(event -> OutboxEvent.RELEASE_STOCK.equals(event.getEventType())));
    }

    /** Records authorizations and can replay or fail on demand. */
    static final class StubPayments implements PaymentGateway {
        private final Queue<PaymentResult> outcomes = new ConcurrentLinkedQueue<>();
        private final Queue<Integer> failures = new ConcurrentLinkedQueue<>();
        private final List<PaymentRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final java.util.Map<String, PaymentResult> ledger = new java.util.concurrent.ConcurrentHashMap<>();

        void respondWith(PaymentResult result) { outcomes.add(result); }

        void failNext(int times) { failures.add(times); }

        void recordPayment(String key, PaymentResult result) { ledger.put(key, result); }

        List<PaymentRequest> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }

        @Override
        public PaymentResult authorize(PaymentRequest request) {
            requests.add(request);
            Integer failuresLeft = failures.peek();
            if (failuresLeft != null && failuresLeft > 0) {
                failures.poll();
                if (failuresLeft > 1) {
                    failures.add(failuresLeft - 1);
                }
                throw new IllegalStateException("psp timeout");
            }
            if (ledger.containsKey(request.idempotencyKey())) {
                return ledger.get(request.idempotencyKey());
            }
            PaymentResult queued = outcomes.poll();
            PaymentResult result = queued == null ? PaymentResult.approved("pay_default") : queued;
            ledger.put(request.idempotencyKey(), result);
            return result;
        }

        @Override
        public Optional<PaymentResult> find(String idempotencyKey) {
            return Optional.ofNullable(ledger.get(idempotencyKey));
        }

        void reset() {
            outcomes.clear();
            failures.clear();
            requests.clear();
            ledger.clear();
        }
    }

    /** Serves one catalog book with a fixed price. */
    static final class CatalogStub {
        private final HttpServer server;
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        private CatalogStub(HttpServer server) { this.server = server; }

        static CatalogStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                CatalogStub stub = new CatalogStub(server);
                server.createContext("/api/catalog/books/", exchange -> {
                    stub.calls.add(exchange.getRequestURI().getPath());
                    byte[] body = "{\"id\":1,\"title\":\"Kubernetes Basics\",\"price\":599.00}"
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                server.setExecutor(null);
                server.start();
                return stub;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        List<String> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        void reset() { calls.clear(); }
    }
}
