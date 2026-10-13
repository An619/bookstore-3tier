package com.anas.bookstore.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "book_id", nullable = false)
    private Long bookId;
    @Column(name = "available_quantity", nullable = false)
    private Integer availableQuantity;

    protected InventoryItem() {}
    public InventoryItem(Long bookId, Integer availableQuantity) {
        this.bookId = bookId; this.availableQuantity = availableQuantity;
    }
    public Long getId() { return id; }
    public Long getBookId() { return bookId; }
    public Integer getAvailableQuantity() { return availableQuantity; }
    public void setAvailableQuantity(Integer availableQuantity) { this.availableQuantity = availableQuantity; }
}
