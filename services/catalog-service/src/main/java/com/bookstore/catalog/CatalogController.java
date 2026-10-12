package com.bookstore.catalog;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private static final List<Book> BOOKS = List.of(
        new Book(1, "DevOps for Beginners", "Bookstore Lab", "DevOps"),
        new Book(2, "Kubernetes Basics", "Bookstore Lab", "Cloud Native"),
        new Book(3, "Reliable CI/CD", "Bookstore Lab", "Engineering")
    );

    @GetMapping("/health")
    public Health health() {
        return new Health("UP", "catalog-service");
    }

    @GetMapping("/catalog/books")
    public List<Book> books() {
        return BOOKS;
    }

    public record Health(String status, String service) {}
}
