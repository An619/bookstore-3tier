package com.anas.bookstore.orders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class OrdersControllerTest {
 @Autowired MockMvc mvc;
 @Autowired OrdersRepository repository;
 @Test void listsOrders() throws Exception {
  mvc.perform(get("/api/orders")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("CREATED"));
 }
 @Test void createsValidOrder() throws Exception {
  mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
   .content("{\"customerName\":\"Test Customer\",\"bookId\":1,\"quantity\":2}"))
   .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED"));
 }
 @Test void rejectsInvalidOrder() throws Exception {
  mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
   .content("{\"customerName\":\"\",\"bookId\":1,\"quantity\":0}"))
   .andExpect(status().isBadRequest());
 }
 @Test void deletesExistingOrder() throws Exception {
  long id = repository.findAllByOrderByIdAsc().get(0).getId();
  mvc.perform(delete("/api/orders/" + id)).andExpect(status().isNoContent());
  mvc.perform(get("/api/orders/" + id)).andExpect(status().isNotFound());
 }
 @Test void deletingUnknownOrderIsNotFound() throws Exception {
  mvc.perform(delete("/api/orders/999999")).andExpect(status().isNotFound());
 }
}
