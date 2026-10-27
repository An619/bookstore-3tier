package com.anas.bookstore.orders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutAttemptRepository extends JpaRepository<CheckoutAttempt, String> {
    /** Attempts the worker gave up on; the reconciler settles them automatically. */
    List<CheckoutAttempt> findTop20ByStatusOrderByIdempotencyKeyAsc(String status);

    long countByStatus(String status);
}
