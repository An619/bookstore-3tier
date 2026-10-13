package com.anas.bookstore.orders;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

@Entity
@Table(name = "customer_orders")
public class OrderRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_name", nullable = false)
    private String customerName;
    @Column(name = "book_id", nullable = false)
    private Long bookId;
    @Column(name = "quantity", nullable = false)
    private Integer quantity;
    @Column(name = "status", nullable = false)
    private String status;

    protected OrderRecord() {}
    public OrderRecord(String customerName, Long bookId, Integer quantity, String status) {
        this.customerName = customerName; this.bookId = bookId; this.quantity = quantity; this.status = status;
    }
    public Long getId() { return id; }
    public String getCustomerName() { return customerName; }
    public Long getBookId() { return bookId; }
    public Integer getQuantity() { return quantity; }
    public String getStatus() { return status; }
}
