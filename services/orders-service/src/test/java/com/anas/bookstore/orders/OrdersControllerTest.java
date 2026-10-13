package com.anas.bookstore.orders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class OrdersControllerTest {
    @Autowired MockMvc mvc;
    @Test void listsOrders() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("CREATED"));
    }
}
