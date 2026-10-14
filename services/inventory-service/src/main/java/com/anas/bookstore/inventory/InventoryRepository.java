package com.anas.bookstore.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findAllByOrderByBookIdAsc();
    Optional<InventoryItem> findByBookId(Long bookId);

    @Modifying
    @Transactional
    @Query("UPDATE InventoryItem i SET i.availableQuantity = i.availableQuantity - :qty " +
           "WHERE i.bookId = :bookId AND i.availableQuantity >= :qty")
    int decrementAvailableQuantity(@Param("bookId") Long bookId, @Param("qty") int qty);

    @Modifying
    @Transactional
    @Query("UPDATE InventoryItem i SET i.availableQuantity = i.availableQuantity + :qty " +
           "WHERE i.bookId = :bookId")
    int incrementAvailableQuantity(@Param("bookId") Long bookId, @Param("qty") int qty);
}
