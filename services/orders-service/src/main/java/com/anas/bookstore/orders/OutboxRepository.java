package com.anas.bookstore.orders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop20ByStatusOrderByIdAsc(String status);

    List<OutboxEvent> findByAggregateKey(String aggregateKey);

    long countByStatus(String status);
}
