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
    boolean existsByBookId(Long bookId);

    // A single conditional UPDATE prevents two concurrent requests from reserving the same final units.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update InventoryItem i
           set i.availableQuantity = i.availableQuantity - :quantity
         where i.bookId = :bookId
           and i.availableQuantity >= :quantity
        """)
    int reserveIfAvailable(@Param("bookId") Long bookId, @Param("quantity") int quantity);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update InventoryItem i
           set i.availableQuantity = i.availableQuantity + :quantity
         where i.bookId = :bookId
        """)
    int releaseQuantity(@Param("bookId") Long bookId, @Param("quantity") int quantity);
}
