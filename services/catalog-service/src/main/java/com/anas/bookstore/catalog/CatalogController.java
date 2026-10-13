package com.anas.bookstore.catalog;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/books")
public class CatalogController {
    private final CatalogBookRepository repository;

    public CatalogController(CatalogBookRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CatalogBook> listBooks() {
        return repository.findAllByOrderByIdAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CatalogBook> getBook(@PathVariable long id) {
        return repository.findById(id).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
