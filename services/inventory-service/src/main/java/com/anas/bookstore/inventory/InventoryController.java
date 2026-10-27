package com.anas.bookstore.inventory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    /** 8-64 characters; the orders worker sends "<checkout-key>:reserve". */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    private final InventoryRepository repository;
    private final InventoryOperationRepository operations;

    public InventoryController(InventoryRepository repository, InventoryOperationRepository operations) {
        this.repository = repository;
        this.operations = operations;
    }

    @GetMapping
    public List<InventoryItem> list() { return repository.findAllByOrderByBookIdAsc(); }

    @GetMapping("/{bookId}")
    public InventoryItem get(@PathVariable long bookId) {
        return repository.findByBookId(bookId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Read-only lookup of a recorded operation by its idempotency key.
     *
     * This is what lets the orders service settle an ambiguous checkout: if the
     * key is present the reservation landed (even when the HTTP response was
     * lost), if it is absent nothing was reserved and a retry is safe.
     */
    @GetMapping("/operations/{idempotencyKey}")
    public InventoryOperation getOperation(@PathVariable String idempotencyKey) {
        return operations.findById(idempotencyKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no operation recorded for this key"));
    }

    @Transactional
    @PostMapping("/{bookId}/reserve")
    public InventoryItem reserve(
        @PathVariable long bookId,
        @RequestParam(defaultValue = "1") int quantity,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        requirePositive(quantity);
        Optional<InventoryOperation> replay = replay(idempotencyKey, InventoryOperation.RESERVE, bookId, quantity);
        if (replay.isPresent()) {
            return get(bookId);
        }
        InventoryItem item = repository.findByBookIdForUpdate(bookId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (item.getAvailableQuantity() < quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient stock");
        }
        item.setAvailableQuantity(item.getAvailableQuantity() - quantity);
        InventoryItem saved = repository.save(item);
        record(idempotencyKey, InventoryOperation.RESERVE, bookId, quantity, saved.getAvailableQuantity());
        return saved;
    }

    @Transactional
    @PostMapping("/{bookId}/release")
    public InventoryItem release(
        @PathVariable long bookId,
        @RequestParam(defaultValue = "1") int quantity,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        requirePositive(quantity);
        Optional<InventoryOperation> replay = replay(idempotencyKey, InventoryOperation.RELEASE, bookId, quantity);
        if (replay.isPresent()) {
            return get(bookId);
        }
        InventoryItem item = repository.findByBookIdForUpdate(bookId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (quantity > Integer.MAX_VALUE - item.getAvailableQuantity()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity would overflow stock capacity");
        }
        item.setAvailableQuantity(item.getAvailableQuantity() + quantity);
        InventoryItem saved = repository.save(item);
        record(idempotencyKey, InventoryOperation.RELEASE, bookId, quantity, saved.getAvailableQuantity());
        return saved;
    }

    private static void requirePositive(int quantity) {
        if (quantity < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
    }

    /**
     * Returns the previous operation when the same key already performed it.
     * Reusing a key for a different operation or payload is a client error.
     */
    private Optional<InventoryOperation> replay(String key, String operation, long bookId, int quantity) {
        if (key == null) {
            return Optional.empty();
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Idempotency-Key must be 8-64 letters, digits or . _ : -");
        }
        Optional<InventoryOperation> existing = operations.findById(key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        InventoryOperation previous = existing.get();
        if (!previous.getOperation().equals(operation)
            || previous.getBookId() != bookId
            || previous.getQuantity() != quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency-Key was already used for a different operation");
        }
        return Optional.of(previous);
    }

    private void record(String key, String operation, long bookId, int quantity, int resultingQuantity) {
        if (key != null) {
            operations.save(new InventoryOperation(key, operation, bookId, quantity, resultingQuantity));
        }
    }
}
