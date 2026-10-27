package com.anas.bookstore.orders;

import java.util.Optional;

/**
 * Payment authorization, isolated behind an SPI.
 *
 * <p>Card data never enters this repository: an adapter talks to a PSP (Stripe,
 * Adyen, Razorpay, ...) with the provider's own tokenization and returns only an
 * approval/decline plus a reference. The order flow treats payment as another
 * step of the checkout saga, so a decline compensates the stock reservation.
 *
 * <p>Two rules an implementation must honour:
 *
 * <ul>
 *   <li>{@code authorize} is idempotent per {@link PaymentRequest#idempotencyKey()}:
 *       a retry after a timeout must return the original outcome, never charge
 *       twice;</li>
 *   <li>{@code find} must answer "what did that key do?" so the reconciler can
 *       settle a checkout whose response was lost.</li>
 * </ul>
 */
public interface PaymentGateway {

    PaymentResult authorize(PaymentRequest request);

    /** The recorded outcome for a key, or empty when nothing was charged. */
    Optional<PaymentResult> find(String idempotencyKey);

    record PaymentRequest(String idempotencyKey, long orderId, long bookId, int quantity,
                          long amountMinor, String currency) {}

    record PaymentResult(Status status, String reference, String message) {
        public enum Status { APPROVED, DECLINED }

        public static PaymentResult approved(String reference) {
            return new PaymentResult(Status.APPROVED, reference, "Approved");
        }

        public static PaymentResult declined(String message) {
            return new PaymentResult(Status.DECLINED, null, message);
        }
    }
}
