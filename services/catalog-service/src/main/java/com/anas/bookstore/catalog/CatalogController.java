package com.anas.bookstore.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/books")
public class CatalogController {
    private final List<CatalogBook> books = List.of(
        new CatalogBook(1, "DevOps for Beginners", "A. Sharma", "DevOps", 49900),
        new CatalogBook(2, "Kubernetes Basics", "R. Patel", "Cloud Native", 59900),
        new CatalogBook(3, "Mastering Jenkins", "S. Khan", "CI/CD", 64900)
    );

    @GetMapping
    public List<CatalogBook> listBooks() {
        return books;
    }

    @GetMapping("/{id}")
    public ResponseEntity<CatalogBook> getBook(@PathVariable long id) {
        Optional<CatalogBook> match = books.stream().filter(book -> book.id() == id).findFirst();
        return match.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
