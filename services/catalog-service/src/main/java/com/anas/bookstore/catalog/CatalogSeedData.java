package com.anas.bookstore.catalog;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogSeedData {
    @Bean
    ApplicationRunner seedCatalog(CatalogBookRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                repository.saveAll(List.of(
                    new CatalogBook("DevOps for Beginners", "John Doe", "DevOps", new BigDecimal("499.00")),
                    new CatalogBook("Kubernetes Basics", "Jane Doe", "Cloud Native", new BigDecimal("599.00")),
                    new CatalogBook("Mastering Jenkins", "Alex Smith", "CI/CD", new BigDecimal("699.00"))
                ));
            }
        };
    }
}
