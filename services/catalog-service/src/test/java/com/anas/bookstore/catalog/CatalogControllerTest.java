package com.anas.bookstore.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogControllerTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void listsCatalogBooks() throws Exception {
        mockMvc.perform(get("/api/catalog/books"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("DevOps for Beginners"))
            .andExpect(jsonPath("$[1].title").value("Kubernetes Basics"));
    }

    @Test
    void returnsOneBookById() throws Exception {
        mockMvc.perform(get("/api/catalog/books/3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Mastering Jenkins"));
    }

    @Test
    void returnsNotFoundForUnknownBook() throws Exception {
        mockMvc.perform(get("/api/catalog/books/999")).andExpect(status().isNotFound());
    }
}
