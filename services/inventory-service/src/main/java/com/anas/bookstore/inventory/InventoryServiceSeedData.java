package com.anas.bookstore.inventory;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class InventoryServiceSeedData {
    @Bean ApplicationRunner seedInventoryService(InventoryRepository repository) {
        return args -> { if (repository.count() == 0) { repository.saveAll(List.of(new InventoryItem(1L, 25), new InventoryItem(2L, 18), new InventoryItem(3L, 12))); } };
    }
}
