package com.anas.bookstore.orders;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Server-to-server client for the inventory service.
 *
 * Every mutating call carries an idempotency key so the outbox worker can retry
 * safely: inventory records the key and replays the previous result instead of
 * reserving or releasing stock twice.
 */
@Component
public class InventoryClient {
    private final RestClient client;

    public InventoryClient(RestClient.Builder builder, @Value("${inventory.service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Bound the wait so a worker cycle cannot hang on an unresponsive dependency.
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void reserve(long bookId, int quantity, String idempotencyKey) {
        call("/reserve", bookId, quantity, idempotencyKey);
    }

    public void release(long bookId, int quantity, String idempotencyKey) {
        call("/release", bookId, quantity, idempotencyKey);
    }

    /**
     * Returns the inventory operation recorded for this key, or empty when the
     * key was never applied. Used to settle checkouts whose response was lost.
     */
    public Optional<InventoryOperationRecord> findOperation(String idempotencyKey) {
        return client.get()
            .uri("/api/inventory/operations/{key}", idempotencyKey)
            .exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404) {
                    return Optional.<InventoryOperationRecord>empty();
                }
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Inventory operation lookup failed with " + status);
                }
                InventoryOperationRecord record = response.bodyTo(InventoryOperationRecord.class);
                if (record == null) {
                    throw new IllegalStateException("Inventory operation lookup returned an empty body");
                }
                return Optional.of(record);
            });
    }

    /** The part of the inventory ledger the outbox actually reasons about. */
    public record InventoryOperationRecord(String operation, Long bookId, Integer quantity,
                                           Integer resultingQuantity) {}

    private void call(String action, long bookId, int quantity, String idempotencyKey) {
        client.post()
            .uri(uri -> uri.path("/api/inventory/{bookId}" + action)
                .queryParam("quantity", quantity)
                .build(bookId))
            .header("Idempotency-Key", idempotencyKey)
            .retrieve()
            .toBodilessEntity();
    }
}
