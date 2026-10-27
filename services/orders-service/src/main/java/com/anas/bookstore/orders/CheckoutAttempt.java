package com.anas.bookstore.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Records the outcome of a checkout so a retried request with the same
 * idempotency key cannot reserve stock or create a second order.
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

    public CheckoutAttempt(String idempotencyKey, String status, int responseStatus, String message) {
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.responseStatus = responseStatus;
        this.message = message;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getMessage() { return message; }

    void complete(Long orderId) {
        this.orderId = orderId;
        this.status = COMPLETED;
        this.responseStatus = 201;
        this.message = null;
    }

    void fail(String result, int httpStatus, String reason) {
        this.status = result;
        this.responseStatus = httpStatus;
        this.message = reason;
    }
}
