package com.anas.bookstore.orders;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrdersController {
    private final OrdersRepository repository;
    public OrdersController(OrdersRepository repository) { this.repository = repository; }

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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerName, positive bookId and positive quantity are required");
        }
        return repository.save(new OrderRecord(request.customerName().trim(), request.bookId(), request.quantity(), "CREATED"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        // Deleting does not release inventory: callers must compensate stock explicitly.
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        repository.deleteById(id);
    }

    public record CreateOrder(String customerName, Long bookId, Integer quantity) {}
}
