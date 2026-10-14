package com.anas.bookstore.inventory;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryRepository repository;

    public InventoryController(InventoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<InventoryItem> list() {
        return repository.findAllByOrderByBookIdAsc();
    }

    @GetMapping("/{bookId}")
    public InventoryItem get(@PathVariable long bookId) {
        return repository.findByBookId(bookId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found"));
    }

    @PostMapping("/{bookId}/reserve")
    public InventoryItem reserve(@PathVariable long bookId,
                                 @RequestParam(defaultValue = "1") int quantity) {
        if (quantity < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
        }

        int updated = repository.reserveIfAvailable(bookId, quantity);
        if (updated == 0) {
            if (!repository.existsByBookId(bookId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient stock");
        }
        return get(bookId);
    }

    @PostMapping("/{bookId}/release")
    public InventoryItem release(@PathVariable long bookId,
                                 @RequestParam(defaultValue = "1") int quantity) {
        if (quantity < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
        }

        int updated = repository.releaseQuantity(bookId, quantity);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found");
        }
        return get(bookId);
    }
}
