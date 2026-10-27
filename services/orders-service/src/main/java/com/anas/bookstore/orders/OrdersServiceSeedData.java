package com.anas.bookstore.orders;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class OrdersServiceSeedData {
    @Bean ApplicationRunner seedOrdersService(OrdersRepository repository) {
        return args -> { if (repository.count() == 0) { repository.saveAll(List.of(new OrderRecord("Sample Customer", 1L, 1, OrderRecord.CREATED))); } };
    }
}
