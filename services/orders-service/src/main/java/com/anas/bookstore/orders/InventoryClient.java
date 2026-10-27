package com.anas.bookstore.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Minimal server-to-server client for the inventory service. */
@Component
public class InventoryClient {
    private final RestClient client;

    public InventoryClient(RestClient.Builder builder, @Value("${inventory.service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Bound the wait so checkout cannot hang on an unresponsive dependency.
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void reserve(long bookId, int quantity) {
        call("/reserve", bookId, quantity);
    }

    public void release(long bookId, int quantity) {
        call("/release", bookId, quantity);
    }

    private void call(String action, long bookId, int quantity) {
        client.post()
            .uri(uri -> uri.path("/api/inventory/{bookId}" + action)
                .queryParam("quantity", quantity)
                .build(bookId))
            .retrieve()
            .toBodilessEntity();
    }
}
