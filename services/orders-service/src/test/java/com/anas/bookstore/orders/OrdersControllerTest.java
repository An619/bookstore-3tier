package com.anas.bookstore.orders;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrdersControllerTest {
    @Autowired MockMvc mvc;
    @MockBean InventoryClient inventoryClient;

    @BeforeEach void setup() {
        when(inventoryClient.tryReserve(anyLong(), anyInt())).thenReturn(true);
    }
    @Test void listsOrders() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isOk());
    }
    @Test void createsValidOrder() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"Test Customer\",\"bookId\":1,\"quantity\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("CREATED"));
    }
    @Test void rejectsInvalidOrder() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"\",\"bookId\":1,\"quantity\":0}"))
            .andExpect(status().isBadRequest());
    }
    @Test void returns409WhenInventoryRejects() throws Exception {
        when(inventoryClient.tryReserve(anyLong(), anyInt())).thenReturn(false);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"Test Customer\",\"bookId\":1,\"quantity\":2}"))
            .andExpect(status().isConflict());
    }
}
