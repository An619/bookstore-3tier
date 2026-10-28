package com.anas.bookstore.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Provider side of the orders -> inventory contract. The same
 * contracts/inventory-api.json is verified by orders-service, so renaming a
 * field here (or expecting a different one there) fails both builds.
 */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class InventoryContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired InventoryRepository repository;

    private JsonNode contract;

    @BeforeEach
    void loadContract() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/contracts/inventory-api.json")) {
            assertNotNull(stream, "contracts/inventory-api.json must be on the test classpath");
            contract = JSON.readTree(stream);
        }
        InventoryItem item = repository.findByBookId(1L).orElseThrow();
        item.setAvailableQuantity(25);
        repository.saveAndFlush(item);
    }

    private JsonNode endpoint(String method, String path) {
        for (JsonNode endpoint : contract.get("endpoints")) {
            if (endpoint.get("method").asText().equals(method) && endpoint.get("path").asText().equals(path)) {
                return endpoint;
            }
        }
        throw new AssertionError("Contract does not document " + method + " " + path);
    }

    /** Asserts the body has the documented fields with the documented JSON types. */
    private void assertItemMatchesContract(String body) throws Exception {
        JsonNode item = JSON.readTree(body);
        JsonNode fields = contract.get("itemFields");
        fields.fieldNames().forEachRemaining(name -> {
            assertTrue(item.has(name), "contract field missing from response: " + name);
            String expected = fields.get(name).asText();
            String actual = item.get(name).isNumber() ? "number" : item.get(name).isTextual() ? "string" : "other";
            assertEquals(expected, actual, "type mismatch for " + name);
        });
    }

    @Test
    void everyDocumentedEndpointExistsInTheApplication() {
        List<String> documented = new ArrayList<>();
        for (JsonNode endpoint : contract.get("endpoints")) {
            documented.add(endpoint.get("method").asText() + " " + endpoint.get("path").asText());
        }
        assertEquals(List.of(
            "GET /api/inventory",
            "GET /api/inventory/{bookId}",
            "POST /api/inventory/{bookId}/reserve",
            "POST /api/inventory/{bookId}/release",
            "GET /api/inventory/operations/{idempotencyKey}"), documented);
    }

    @Test
    void listAndGetMatchTheDocumentedFieldTypes() throws Exception {
        MvcResult list = mvc.perform(get("/api/inventory")).andReturn();
        assertTrue(documentedStatuses("GET", "/api/inventory").contains(200),
            "contract must document 200 for the list endpoint");
        assertEquals(200, list.getResponse().getStatus());
        JsonNode body = JSON.readTree(list.getResponse().getContentAsByteArray());
        assertTrue(body.isArray(), "GET /api/inventory must return an array");
        assertItemMatchesContract(body.get(0).toString());

        MvcResult single = mvc.perform(get("/api/inventory/1")).andReturn();
        assertEquals(200, single.getResponse().getStatus());
        assertItemMatchesContract(single.getResponse().getContentAsString());

        assertEquals(404, mvc.perform(get("/api/inventory/9999")).andReturn().getResponse().getStatus(),
            "undocumented 404 for an unknown book");
    }

    @Test
    void reserveMatchesTheDocumentedStatuses() throws Exception {
        Set<Integer> statuses = documentedStatuses("POST", "/api/inventory/{bookId}/reserve");
        assertTrue(statuses.containsAll(Set.of(200, 400, 409)), "contract must document 200/400/409");

        MvcResult ok = mvc.perform(post("/api/inventory/1/reserve").param("quantity", "2")).andReturn();
        assertEquals(200, ok.getResponse().getStatus());
        assertItemMatchesContract(ok.getResponse().getContentAsString());

        assertEquals(400, mvc.perform(post("/api/inventory/1/reserve").param("quantity", "0"))
            .andReturn().getResponse().getStatus());
        assertEquals(409, mvc.perform(post("/api/inventory/1/reserve").param("quantity", "100000"))
            .andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(post("/api/inventory/9999/reserve").param("quantity", "1"))
            .andReturn().getResponse().getStatus());
    }

    @Test
    void releaseMatchesTheDocumentedStatuses() throws Exception {
        Set<Integer> statuses = documentedStatuses("POST", "/api/inventory/{bookId}/release");
        assertTrue(statuses.containsAll(Set.of(200, 400)), "contract must document 200/400");
        MvcResult ok = mvc.perform(post("/api/inventory/1/release").param("quantity", "1")).andReturn();
        assertEquals(200, ok.getResponse().getStatus());
        assertItemMatchesContract(ok.getResponse().getContentAsString());
        assertEquals(400, mvc.perform(post("/api/inventory/1/release").param("quantity", "-1"))
            .andReturn().getResponse().getStatus());
    }

    @Test
    void idempotencyKeyIsReplaySafeAsDocumented() throws Exception {
        String documented = endpoint("POST", "/api/inventory/{bookId}/reserve")
            .get("requestHeaders").get("Idempotency-Key").asText();
        assertTrue(documented.contains("8-64"), "contract must describe the key format");

        int before = repository.findByBookId(1L).orElseThrow().getAvailableQuantity();
        assertEquals(200, mvc.perform(post("/api/inventory/1/reserve").param("quantity", "2")
            .header("Idempotency-Key", "contract-key-0001")).andReturn().getResponse().getStatus());
        // A second call with the same key must not consume stock again.
        assertEquals(200, mvc.perform(post("/api/inventory/1/reserve").param("quantity", "2")
            .header("Idempotency-Key", "contract-key-0001")).andReturn().getResponse().getStatus());
        assertEquals(before - 2, repository.findByBookId(1L).orElseThrow().getAvailableQuantity());
    }

    @Test
    void operationLookupMatchesTheDocumentedFieldTypes() throws Exception {
        Set<Integer> statuses = documentedStatuses("GET", "/api/inventory/operations/{idempotencyKey}");
        assertTrue(statuses.containsAll(Set.of(200, 404)), "contract must document 200/404");

        String key = "contract-lookup-0001:reserve";
        assertEquals(200, mvc.perform(post("/api/inventory/1/reserve").param("quantity", "2")
            .header("Idempotency-Key", key)).andReturn().getResponse().getStatus());

        MvcResult recorded = mvc.perform(get("/api/inventory/operations/" + key)).andReturn();
        assertEquals(200, recorded.getResponse().getStatus());
        JsonNode body = JSON.readTree(recorded.getResponse().getContentAsByteArray());
        JsonNode fields = contract.get("operationFields");
        fields.fieldNames().forEachRemaining(name -> {
            assertTrue(body.has(name), "contract field missing from response: " + name);
            String expected = fields.get(name).asText();
            String actual = body.get(name).isNumber() ? "number" : body.get(name).isTextual() ? "string" : "other";
            assertEquals(expected, actual, "type mismatch for " + name);
        });
        assertEquals("RESERVE", body.get("operation").asText());
        assertEquals(2, body.get("quantity").asInt());

        // An unknown key is a 404, which is what makes a retry safe for the caller.
        assertEquals(404, mvc.perform(get("/api/inventory/operations/never-applied-key"))
            .andReturn().getResponse().getStatus());
    }

    private Set<Integer> documentedStatuses(String method, String path) {
        Set<Integer> statuses = new java.util.HashSet<>();
        endpoint(method, path).get("responses").fieldNames()
            .forEachRemaining(code -> statuses.add(Integer.valueOf(code)));
        return statuses;
    }
}
