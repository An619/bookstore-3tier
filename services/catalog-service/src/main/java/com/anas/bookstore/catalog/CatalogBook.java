package com.anas.bookstore.catalog;

import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "catalog_books")
public class CatalogBook {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false, length = 120) private String author;
    @Column(nullable = false, length = 80) private String category;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal price;

    protected CatalogBook() {}

    public CatalogBook(String title, String author, String category, BigDecimal price) {
        this.title = title; this.author = author; this.category = category; this.price = price;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
}
