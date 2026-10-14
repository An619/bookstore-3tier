package com.anas.bookstore.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InventoryClient {
    private final RestClient client;
    public InventoryClient(@Value("${inventory.base-url:http://inventory-service:8083}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }
    public boolean tryReserve(long bookId, int quantity) {
        HttpStatusCode code = client.post()
            .uri("/api/inventory/{id}/reserve?quantity={q}", bookId, quantity)
            .exchange((req, res) -> res.getStatusCode());
        if (code.value() == 200) return true;
        if (code.value() == 409) return false;
        if (code.value() == 404) throw new IllegalStateException("book not found");
        throw new IllegalStateException("reserve failed: HTTP " + code.value());
    }
    public void release(long bookId, int quantity) {
        client.post().uri("/api/inventory/{id}/release?quantity={q}", bookId, quantity)
            .retrieve().toBodilessEntity();
    }
}
