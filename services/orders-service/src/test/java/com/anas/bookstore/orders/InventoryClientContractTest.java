package com.anas.bookstore.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Consumer side of the orders -> inventory contract: asserts the requests this
 * service sends match what contracts/inventory-api.json documents, which
 * inventory-service also verifies as the provider.
 */
class InventoryClientContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer server;
    private static final List<Recorded> REQUESTS = new CopyOnWriteArrayList<>();
    private static JsonNode contract;

    record Recorded(String method, String path, String query, String idempotencyKey) {
        String apiPath() { return path.replaceFirst("^/api/inventory", "/api/inventory"); }
    }

    private InventoryClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/inventory/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            REQUESTS.add(new Recorded(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), query,
                exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            if (exchange.getRequestURI().getPath().startsWith("/api/inventory/operations/")) {
                byte[] body = "{\"operation\":\"RESERVE\",\"bookId\":1,\"quantity\":2,\"resultingQuantity\":23}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() throws Exception {
        REQUESTS.clear();
        try (InputStream stream = getClass().getResourceAsStream("/contracts/inventory-api.json")) {
            assertNotNull(stream, "contracts/inventory-api.json must be on the test classpath");
            contract = JSON.readTree(stream);
        }
        client = new InventoryClient(RestClient.builder(),
            "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private JsonNode endpoint(String method, String path) {
        for (JsonNode endpoint : contract.get("endpoints")) {
            if (endpoint.get("method").asText().equals(method) && endpoint.get("path").asText().equals(path)) {
                return endpoint;
            }
        }
        throw new AssertionError("Contract does not document " + method + " " + path);
    }

    private void assertDocumented(String method, String template, String actualPath, String action) {
        JsonNode documented = endpoint(method, template);
        assertTrue(documented.get("path").asText().endsWith(action),
            "contract path must end with " + action);
        // Fill the template with the book id this call actually used.
        String[] segments = actualPath.split("/");
        assertEquals(5, segments.length, "unexpected call path: " + actualPath);
        assertEquals(documented.get("path").asText().replace("{bookId}", segments[3]), actualPath);
        assertTrue(documented.get("query").has("quantity"), "contract must document the quantity parameter");
        assertTrue(documented.get("requestHeaders").has("Idempotency-Key"),
            "contract must document the idempotency header");
    }

    @Test
    void reserveSendsTheDocumentedRequest() {
        client.reserve(1L, 2, "contract-key-0001:reserve");

        assertEquals(1, REQUESTS.size());
        Recorded request = REQUESTS.get(0);
        assertEquals("POST", request.method());
        assertDocumented("POST", "/api/inventory/{bookId}/reserve", request.path(), "/reserve");
        assertEquals("quantity=2", request.query());
        assertEquals("contract-key-0001:reserve", request.idempotencyKey());
        assertTrue(request.idempotencyKey().length() <= 64, "key must fit the documented 8-64 range");
    }

    @Test
    void releaseSendsTheDocumentedRequest() {
        client.release(3L, 1, "contract-key-0002:release");

        Recorded request = REQUESTS.get(0);
        assertEquals("POST", request.method());
        assertDocumented("POST", "/api/inventory/{bookId}/release", request.path(), "/release");
        assertEquals("quantity=1", request.query());
        assertEquals("contract-key-0002:release", request.idempotencyKey());
    }

    @Test
    void operationLookupUsesTheDocumentedEndpoint() {
        var recorded = client.findOperation("contract-key-0004:reserve");

        assertTrue(recorded.isPresent(), "a 200 must be parsed, not discarded");
        assertEquals("RESERVE", recorded.get().operation());
        assertEquals(1L, recorded.get().bookId().longValue());
        assertEquals(2, recorded.get().quantity().intValue());

        Recorded request = REQUESTS.get(0);
        assertEquals("GET", request.method());
        JsonNode documented = endpoint("GET", "/api/inventory/operations/{idempotencyKey}");
        assertEquals(documented.get("path").asText().replace("{idempotencyKey}", "contract-key-0004:reserve"),
            request.path());
        assertNull(request.idempotencyKey(), "a read must not invent an idempotency header");
    }

    @Test
    void everyCallCarriesAnIdempotencyKey() {
        client.reserve(1L, 1, "contract-key-0003:reserve");
        client.release(1L, 1, "contract-key-0003:release");
        for (Recorded request : REQUESTS) {
            assertNotNull(request.idempotencyKey(), "inventory calls must be idempotent: " + request.path());
        }
    }
}
