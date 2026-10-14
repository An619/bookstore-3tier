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

    /**
     * Atomically reserves stock only when enough units remain. The conditional
     * UPDATE prevents concurrent requests from both reading and selling the
     * same final units.
     *
     * @return number of rows updated (1 on success, 0 when missing/out of stock)
     */
    @Modifying
    @Transactional
    @Query("""
        update InventoryItem item
           set item.availableQuantity = item.availableQuantity - :quantity
         where item.bookId = :bookId
           and item.availableQuantity >= :quantity
        """)
    int reserveIfAvailable(@Param("bookId") Long bookId, @Param("quantity") int quantity);

    @Modifying
    @Transactional
    @Query("""
        update InventoryItem item
           set item.availableQuantity = item.availableQuantity + :quantity
         where item.bookId = :bookId
        """)
    int releaseStock(@Param("bookId") Long bookId, @Param("quantity") int quantity);
}
