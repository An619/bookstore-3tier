package com.anas.bookstore.inventory;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findAllByOrderByBookIdAsc();
    Optional<InventoryItem> findByBookId(Long bookId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from InventoryItem item where item.bookId = :bookId")
    Optional<InventoryItem> findByBookIdForUpdate(@Param("bookId") Long bookId);
}
