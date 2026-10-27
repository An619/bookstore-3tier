package com.anas.bookstore.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Records a stock operation that carried an idempotency key, so an at-least-once
 * caller (the orders outbox worker) can retry without reserving twice.
 */
@Entity
@Table(name = "inventory_operations")
public class InventoryOperation {
    public static final String RESERVE = "RESERVE";
    public static final String RELEASE = "RELEASE";

    @Id
    @Column(name = "idempotency_key", length = 64, nullable = false)
    private String idempotencyKey;
    @Column(name = "operation", length = 16, nullable = false)
    private String operation;
    @Column(name = "book_id", nullable = false)
    private Long bookId;
    @Column(name = "quantity", nullable = false)
    private Integer quantity;
    @Column(name = "resulting_quantity", nullable = false)
    private Integer resultingQuantity;

    protected InventoryOperation() {}

    public InventoryOperation(String idempotencyKey, String operation, Long bookId,
                              Integer quantity, Integer resultingQuantity) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.bookId = bookId;
        this.quantity = quantity;
        this.resultingQuantity = resultingQuantity;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getOperation() { return operation; }
    public Long getBookId() { return bookId; }
    public Integer getQuantity() { return quantity; }
    public Integer getResultingQuantity() { return resultingQuantity; }
}
