package com.anas.bookstore.inventory;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class InventoryControllerTest {
 @Autowired MockMvc mvc;
 @Test void listsStock() throws Exception {
  mvc.perform(get("/api/inventory")).andExpect(status().isOk()).andExpect(jsonPath("$[0].availableQuantity").value(25));
 }
 @Test void reservesAvailableStock() throws Exception {
  mvc.perform(post("/api/inventory/1/reserve").param("quantity","2"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").value(23));
 }
 @Test void rejectsReservationLargerThanStock() throws Exception {
  mvc.perform(post("/api/inventory/1/reserve").param("quantity","100"))
   .andExpect(status().isConflict());
 }
}
