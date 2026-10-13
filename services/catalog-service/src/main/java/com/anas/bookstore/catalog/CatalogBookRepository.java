package com.anas.bookstore.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogBookRepository extends JpaRepository<CatalogBook, Long> {
}
