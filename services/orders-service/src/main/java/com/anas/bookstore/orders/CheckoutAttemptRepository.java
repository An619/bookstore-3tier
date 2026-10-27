package com.anas.bookstore.orders;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutAttemptRepository extends JpaRepository<CheckoutAttempt, String> {
}
