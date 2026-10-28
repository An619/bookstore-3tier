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
    /** Stock reservation has not been confirmed by the inventory service yet. */
    public static final String PENDING = "PENDING";
    /** Inventory confirmed the reservation. */
    public static final String CONFIRMED = "CONFIRMED";
    /** Reservation was rejected; no stock is held. */
    public static final String CANCELLED = "CANCELLED";
    /** Created directly through the direct API without a stock reservation. */
    public static final String CREATED = "CREATED";

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
    public void setStatus(String status) { this.status = status; }
}
