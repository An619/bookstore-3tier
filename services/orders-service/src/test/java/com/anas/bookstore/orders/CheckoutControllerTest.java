package com.anas.bookstore.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class CheckoutControllerTest {
    private static final String BODY =
        "{\"customerName\":\"Checkout Customer\",\"bookId\":1,\"quantity\":2}";

    // Started at class initialisation so it exists before the Spring context.
    static final InventoryStub INVENTORY = InventoryStub.start();

    @DynamicPropertySource
    static void inventoryProperties(DynamicPropertyRegistry registry) {
        registry.add("inventory.service.url", INVENTORY::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired OrdersRepository orders;
    @Autowired CheckoutAttemptRepository attempts;

    private long baselineOrders;

    @BeforeEach
    void reset() {
        INVENTORY.reset();
        // Other test classes share this in-memory database and its seed row, so
        // compare against a baseline instead of deleting orders.
        attempts.deleteAll();
        baselineOrders = orders.count();
    }

    private org.springframework.test.web.servlet.ResultActions checkout(String key) throws Exception {
        var request = post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    @Test void reservesStockAndCreatesOrder() throws Exception {
        checkout("order-key-0001")
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("CREATED"));
        assertEquals(List.of("POST /api/inventory/1/reserve?quantity=2"), INVENTORY.calls());
        assertEquals(baselineOrders + 1, orders.count());
    }

    @Test void replaysWithoutReservingOrDuplicating() throws Exception {
        var first = checkout("order-key-0002").andExpect(status().isCreated()).andReturn();
        Long firstId = com.jayway.jsonpath.JsonPath.read(
            first.getResponse().getContentAsString(), "$.id");
        int callsAfterFirst = INVENTORY.calls().size();

        checkout("order-key-0002")
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.id").value(firstId));
        assertEquals(callsAfterFirst, INVENTORY.calls().size());
        assertEquals(baselineOrders + 1, orders.count());
    }

    @Test void insufficientStockCreatesNoOrder() throws Exception {
        INVENTORY.respondWith(409);
        checkout("order-key-0003")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Insufficient stock"));
        assertEquals(baselineOrders, orders.count());
        assertEquals(CheckoutAttempt.FAILED, attempts.findById("order-key-0003").orElseThrow().getStatus());
    }

    @Test void unknownBookReturnsNotFound() throws Exception {
        INVENTORY.respondWith(404);
        checkout("order-key-0004").andExpect(status().isNotFound());
        assertEquals(baselineOrders, orders.count());
    }

    @Test void inventoryFailureReleasesStock() throws Exception {
        INVENTORY.respondWith(500);
        checkout("order-key-0005")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("Inventory reservation failed; stock state is unconfirmed"));
        assertTrue(INVENTORY.calls().contains("POST /api/inventory/1/release?quantity=2"));
        assertEquals(baselineOrders, orders.count());
    }

    @Test void failedReleaseNeedsReconciliationAndBlocksRetries() throws Exception {
        INVENTORY.respondWith(500);
        INVENTORY.respondWith(500);
        checkout("order-key-0006").andExpect(status().isBadGateway());
        assertEquals(CheckoutAttempt.NEEDS_RECONCILIATION,
            attempts.findById("order-key-0006").orElseThrow().getStatus());

        checkout("order-key-0006")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value(
                "A previous attempt needs reconciliation; check inventory before retrying"));
    }

    @Test void failedCheckoutReplayKeepsOriginalStatus() throws Exception {
        INVENTORY.respondWith(409);
        checkout("order-key-0007").andExpect(status().isConflict());
        checkout("order-key-0007")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Insufficient stock"));
    }

    @Test void rejectsMissingOrMalformedIdempotencyKey() throws Exception {
        checkout(null).andExpect(status().isBadRequest());
        checkout("short").andExpect(status().isBadRequest());
        checkout("contains a space").andExpect(status().isBadRequest());
        assertTrue(INVENTORY.calls().isEmpty());
    }

    @Test void rejectsInvalidBody() throws Exception {
        mvc.perform(post("/api/checkout")
                .header("Idempotency-Key", "order-key-0008")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"\",\"bookId\":0,\"quantity\":0}"))
            .andExpect(status().isBadRequest());
        assertTrue(INVENTORY.calls().isEmpty());
    }

    /** Records requests and returns queued statuses (default 200). */
    static final class InventoryStub {
        private final HttpServer server;
        private final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        private InventoryStub(HttpServer server) { this.server = server; }

        static InventoryStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                InventoryStub stub = new InventoryStub(server);
                server.createContext("/api/inventory/", exchange -> {
                    String query = exchange.getRequestURI().getQuery();
                    stub.calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                        + (query == null ? "" : "?" + query));
                    Integer queued = stub.statuses.poll();
                    byte[] body = new byte[0];
                    int status = queued == null ? 200 : queued;
                    if (status != 200) {
                        body = "{\"error\":\"stub\"}".getBytes(StandardCharsets.UTF_8);
                    }
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                    if (body.length > 0) {
                        exchange.getResponseBody().write(body);
                    }
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

        void respondWith(int status) { statuses.add(status); }

        List<String> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        void reset() {
            statuses.clear();
            calls.clear();
        }
    }
}
