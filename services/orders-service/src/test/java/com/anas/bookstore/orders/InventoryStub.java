package com.anas.bookstore.orders;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-process stand-in for the inventory service.
 *
 * <p>It records every call (path, query and idempotency key) and returns queued
 * statuses for reserve/release. Like the real service it also keeps a ledger of
 * applied operations, so {@code /api/inventory/operations/{key}} answers the
 * question the outbox reconciler asks: did this reservation actually land?
 */
final class InventoryStub {
    static final String OPERATIONS_PATH = "/api/inventory/operations/";

    private final HttpServer server;
    private final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> keys = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> ledger = new ConcurrentHashMap<>();
    private volatile int ledgerFailureStatus;

    private InventoryStub(HttpServer server) {
        this.server = server;
    }

    static InventoryStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            InventoryStub stub = new InventoryStub(server);
            server.createContext("/api/inventory/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getQuery();
                stub.calls.add(exchange.getRequestMethod() + " " + path
                    + (query == null ? "" : "?" + query));
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                stub.keys.add(key == null ? "" : key);

                if (path.startsWith(OPERATIONS_PATH)) {
                    stub.answerLedger(exchange, path.substring(OPERATIONS_PATH.length()));
                    return;
                }
                Integer queued = stub.statuses.poll();
                int status = queued == null ? 200 : queued;
                if (status == 200 && key != null && path.endsWith("/reserve")) {
                    // Mirror inventory: a successful reservation is in the ledger.
                    stub.ledger.put(key, reservationJson(path, query));
                }
                stub.respond(exchange, status, status == 200 ? new byte[0]
                    : "{\"error\":\"stub\"}".getBytes(StandardCharsets.UTF_8));
            });
            server.setExecutor(null);
            server.start();
            return stub;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void answerLedger(com.sun.net.httpserver.HttpExchange exchange, String key) throws IOException {
        int failure = ledgerFailureStatus;
        if (failure != 0) {
            respond(exchange, failure, "{\"error\":\"ledger unavailable\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String recorded = ledger.get(key);
        if (recorded == null) {
            respond(exchange, 404, "{\"error\":\"no operation recorded for this key\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, recorded.getBytes(StandardCharsets.UTF_8));
    }

    private static String reservationJson(String path, String query) {
        String bookId = path.replaceAll(".*/", "").replace("/reserve", "");
        String quantity = query != null && query.startsWith("quantity=") ? query.substring(9) : "1";
        return "{\"operation\":\"RESERVE\",\"bookId\":" + bookId + ",\"quantity\":" + quantity
            + ",\"resultingQuantity\":0}";
    }

    /** Serves a response with no body when {@code status} is 204, a body otherwise. */
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        boolean bodyless = status == 204 || status == 304 || body.length == 0;
        exchange.sendResponseHeaders(status, bodyless ? -1 : body.length);
        if (!bodyless) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respondWith(int status) {
        statuses.add(status);
    }

    /** Forges a reservation that committed even though the caller never saw the reply. */
    void recordReservation(String idempotencyKey, long bookId, int quantity) {
        ledger.put(idempotencyKey, "{\"operation\":\"RESERVE\",\"bookId\":" + bookId
            + ",\"quantity\":" + quantity + ",\"resultingQuantity\":0}");
    }

    /** Forges an unrelated operation reusing the checkout's key. */
    void recordOperation(String idempotencyKey, String operation, long bookId, int quantity) {
        ledger.put(idempotencyKey, "{\"operation\":\"" + operation + "\",\"bookId\":" + bookId
            + ",\"quantity\":" + quantity + ",\"resultingQuantity\":0}");
    }

    /** Makes the ledger endpoint unavailable (0 restores normal behaviour). */
    void failLedgerWith(int status) {
        ledgerFailureStatus = status;
    }

    List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    List<String> idempotencyKeys() {
        synchronized (keys) {
            return List.copyOf(keys);
        }
    }

    void reset() {
        statuses.clear();
        calls.clear();
        keys.clear();
        ledger.clear();
        ledgerFailureStatus = 0;
    }
}
