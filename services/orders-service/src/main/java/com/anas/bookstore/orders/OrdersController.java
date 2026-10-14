package com.anas.bookstore.orders;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrdersController {
    private final OrdersRepository repository;
    private final InventoryClient inventoryClient;

    public OrdersController(OrdersRepository repository, InventoryClient inventoryClient) {
        this.repository = repository;
        this.inventoryClient = inventoryClient;
    }

    @GetMapping
    public List<OrderRecord> list() { return repository.findAllByOrderByIdAsc(); }

    @GetMapping("/{id}")
    public OrderRecord get(@PathVariable long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderRecord create(@RequestBody CreateOrder request) {
        if (request.customerName() == null || request.customerName().isBlank()
            || request.bookId() == null || request.bookId() < 1
            || request.quantity() == null || request.quantity() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "customerName, positive bookId and positive quantity are required");
        }
        boolean reserved;
        try { reserved = inventoryClient.tryReserve(request.bookId(), request.quantity()); }
        catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "inventory unavailable: " + e.getMessage());
        }
        if (!reserved) throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient stock");

        try {
            return repository.save(new OrderRecord(request.customerName().trim(),
                request.bookId(), request.quantity(), "CREATED"));
        } catch (RuntimeException dbError) {
            try { inventoryClient.release(request.bookId(), request.quantity()); }
            catch (RuntimeException ignore) { System.err.println("release failed for book " + request.bookId()); }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "order persist failed: " + dbError.getMessage());
        }
    }
    public record CreateOrder(String customerName, Long bookId, Integer quantity) {}
}
