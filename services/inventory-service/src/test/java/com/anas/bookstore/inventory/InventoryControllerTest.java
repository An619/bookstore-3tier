package com.anas.bookstore.inventory;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class InventoryControllerTest {
 @Autowired MockMvc mvc;
 @Autowired InventoryRepository repository;
 @BeforeEach void resetStock() {
  InventoryItem item = repository.findByBookId(1L).orElseThrow();
  item.setAvailableQuantity(25);
  repository.saveAndFlush(item);
 }
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
 @Test void releasesStock() throws Exception {
  mvc.perform(post("/api/inventory/1/release").param("quantity","2"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").value(27));
 }
 @Test void rejectsInvalidQuantity() throws Exception {
  mvc.perform(post("/api/inventory/1/reserve").param("quantity","0"))
   .andExpect(status().isBadRequest());
  mvc.perform(post("/api/inventory/1/release").param("quantity","-1"))
   .andExpect(status().isBadRequest());
 }
 @Test void rejectsOverflow() throws Exception {
  mvc.perform(post("/api/inventory/1/release").param("quantity",Integer.toString(Integer.MAX_VALUE)))
   .andExpect(status().isBadRequest());
 }
 @Test void concurrentReservationsCannotOversell() throws Exception {
  java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   java.util.concurrent.Callable<Integer> reserve = () -> mvc.perform(
    post("/api/inventory/1/reserve").param("quantity","20")).andReturn().getResponse().getStatus();
   var results = executor.invokeAll(java.util.List.of(reserve, reserve));
   var statuses = java.util.List.of(results.get(0).get(), results.get(1).get());
   org.junit.jupiter.api.Assertions.assertTrue(statuses.contains(200));
   org.junit.jupiter.api.Assertions.assertTrue(statuses.contains(409));
   mvc.perform(get("/api/inventory/1")).andExpect(jsonPath("$.availableQuantity").value(5));
  } finally {
   executor.shutdownNow();
  }
 }
}
