package com.anas.bookstore.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Transactional outbox row. It is written in the same transaction as the order,
 * so a crash can never lose the intent to reserve stock.
 */
@Entity
@Table(name = "checkout_outbox")
public class OutboxEvent {
    public static final String RESERVE_STOCK = "RESERVE_STOCK";
    /** Compensating event: give the reserved stock back after a failed checkout. */
    public static final String RELEASE_STOCK = "RELEASE_STOCK";
    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "aggregate_key", length = 64, nullable = false)
    private String aggregateKey;
    @Column(name = "event_type", length = 32, nullable = false)
    private String eventType;
    @Column(name = "book_id", nullable = false)
    private Long bookId;
    @Column(name = "quantity", nullable = false)
    private Integer quantity;
    @Column(name = "status", length = 16, nullable = false)
    private String status;
    @Column(name = "attempts", nullable = false)
    private Integer attempts;
    @Column(name = "reconcile_cycles", nullable = false)
    private Integer reconcileCycles;
    @Column(name = "last_error", length = 200)
    private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateKey, String eventType, Long bookId, Integer quantity) {
        this.aggregateKey = aggregateKey;
        this.eventType = eventType;
        this.bookId = bookId;
        this.quantity = quantity;
        this.status = PENDING;
        this.attempts = 0;
        this.reconcileCycles = 0;
    }

    public Long getId() { return id; }
    public String getAggregateKey() { return aggregateKey; }
    public String getEventType() { return eventType; }
    public Long getBookId() { return bookId; }
    public Integer getQuantity() { return quantity; }
    public String getStatus() { return status; }
    public Integer getAttempts() { return attempts; }
    public Integer getReconcileCycles() { return reconcileCycles; }
    public String getLastError() { return lastError; }

    public void incrementAttempts() { this.attempts = this.attempts + 1; }

    /**
     * Puts the event back in the worker queue after reconciliation proved the
     * reservation never landed. The cycle counter bounds the total retries.
     */
    public void requeue() {
        this.status = PENDING;
        this.attempts = 0;
        this.reconcileCycles = this.reconcileCycles + 1;
    }

    public void done() {
        this.status = DONE;
        this.lastError = null;
    }

    public void failed(String error) {
        this.status = FAILED;
        this.lastError = truncate(error);
    }

    public void retryLater(String error) {
        this.status = PENDING;
        this.lastError = truncate(error);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
