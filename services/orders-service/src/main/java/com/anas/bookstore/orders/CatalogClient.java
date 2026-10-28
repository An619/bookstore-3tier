package com.anas.bookstore.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the price of a book from the catalog service so the payment step charges
 * a server-side amount. The client never takes an amount from the browser.
 */
@Component
public class CatalogClient {
    private final RestClient client;

    public CatalogClient(RestClient.Builder builder,
                         @Value("${catalog.service.url:http://catalog-service:8081}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Price of one unit in minor units (paise/cents), e.g. 599.00 -> 59900. */
    public long unitPriceMinor(long bookId) {
        Book book = client.get().uri("/api/catalog/books/{id}", bookId).retrieve().body(Book.class);
        if (book == null || book.price() == null) {
            throw new IllegalStateException("Catalog returned no price for book " + bookId);
        }
        try {
            return book.price().movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalStateException("Catalog price has more than two decimals: " + book.price());
        }
    }

    record Book(BigDecimal price) {}
}
