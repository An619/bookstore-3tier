package com.anas.bookstore.inventory;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryRepository repository;
    public InventoryController(InventoryRepository repository) { this.repository = repository; }

    @GetMapping
    public List<InventoryItem> list() { return repository.findAllByOrderByBookIdAsc(); }

    @GetMapping("/{bookId}")
    public InventoryItem get(@PathVariable long bookId) {
        return repository.findByBookId(bookId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    @PostMapping("/{bookId}/reserve")
    public InventoryItem reserve(@PathVariable long bookId, @RequestParam(defaultValue = "1") int quantity) {
        if (quantity < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
        InventoryItem item = repository.findByBookIdForUpdate(bookId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (item.getAvailableQuantity() < quantity) throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient stock");
        item.setAvailableQuantity(item.getAvailableQuantity() - quantity);
        return repository.save(item);
    }

    @Transactional
    @PostMapping("/{bookId}/release")
    public InventoryItem release(@PathVariable long bookId, @RequestParam(defaultValue = "1") int quantity) {
        if (quantity < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
        InventoryItem item = repository.findByBookIdForUpdate(bookId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (quantity > Integer.MAX_VALUE - item.getAvailableQuantity()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity would overflow stock capacity");
        }
        item.setAvailableQuantity(item.getAvailableQuantity() + quantity);
        return repository.save(item);
    }
}
