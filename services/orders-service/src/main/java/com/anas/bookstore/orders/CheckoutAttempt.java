package com.anas.bookstore.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Durable outcome of a checkout keyed by its idempotency key: the order it
 * created and whether stock reservation completed, failed or needs a human.
 */
@Entity
@Table(name = "checkout_attempts")
public class CheckoutAttempt {
    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION";

    @Id
    @Column(name = "idempotency_key", length = 64, nullable = false)
    private String idempotencyKey;
    @Column(name = "order_id")
    private Long orderId;
    @Column(name = "status", length = 32, nullable = false)
    private String status;
    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;
    @Column(name = "message", length = 200)
    private String message;

    protected CheckoutAttempt() {}

    public CheckoutAttempt(String idempotencyKey, Long orderId, String status,
                           int responseStatus, String message) {
        this.idempotencyKey = idempotencyKey;
        this.orderId = orderId;
        this.status = status;
        this.responseStatus = responseStatus;
        this.message = message;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getMessage() { return message; }

    public void complete(int responseStatus, String message) {
        this.status = COMPLETED;
        this.responseStatus = responseStatus;
        this.message = truncate(message);
    }

    /** Reconciliation found no reservation and re-queued the checkout. */
    public void retryAfterReconciliation(String message) {
        this.status = PENDING;
        this.responseStatus = 202;
        this.message = truncate(message);
    }

    public void fail(int responseStatus, String message) {
        this.status = FAILED;
        this.responseStatus = responseStatus;
        this.message = truncate(message);
    }

    public void needsReconciliation(int responseStatus, String message) {
        this.status = NEEDS_RECONCILIATION;
        this.responseStatus = responseStatus;
        this.message = truncate(message);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
