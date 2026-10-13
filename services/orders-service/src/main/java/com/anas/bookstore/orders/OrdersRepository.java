package com.anas.bookstore.orders;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface OrdersRepository extends JpaRepository<OrderRecord, Long> {
    List<OrderRecord> findAllByOrderByIdAsc();
}
