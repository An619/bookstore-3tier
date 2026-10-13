package com.anas.bookstore.inventory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findAllByOrderByBookIdAsc();
    Optional<InventoryItem> findByBookId(Long bookId);
}
