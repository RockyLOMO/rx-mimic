package org.rx.crawler.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.config.TaskApiProperties;
import org.rx.crawler.task.common.ClientCashbackQueueStore;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CashbackQueueControllerTest {
    @TempDir
    Path tempDir;

    private EntityDatabase db;
    private ObjectMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        db = new EntityDatabaseImpl(tempDir.resolve("cashback-queue-test").toString(), null);
        mapper = new ObjectMapper();
        ClientCashbackQueueStore store = new ClientCashbackQueueStore(db, mapper);
        store.init();

        TaskApiProperties props = new TaskApiProperties();
        props.setApiKeys(Map.of("alice", "alice-secret", "bob", "bob-secret"));

        CashbackQueueController controller = new CashbackQueueController(store);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new TaskApiAuthenticationFilter(props, mapper))
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void unauthorizedAndInvalidDeviceIdFailClosed() throws Exception {
        mvc.perform(get("/api/v1/cashback/queue").header("X-Device-Id", "device-a"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/cashback/queue").header("Authorization", "Bearer wrong").header("X-Device-Id", "device-a"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/cashback/queue").header("Authorization", "Bearer alice-secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEVICE_ID"));

        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "bad device/id")
                        .contentType("application/json")
                        .content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEVICE_ID"));
    }

    @Test
    void queueIsIsolatedByCallerAndDeviceId() throws Exception {
        String aliceDeviceA = "[{\"id\":\"t-a1\",\"rawContent\":\"https://item.jd.com/1.html\",\"extractedUrl\":\"https://item.jd.com/1.html\",\"platform\":\"JD\"}]";
        String aliceDeviceB = "[{\"id\":\"t-b1\",\"rawContent\":\"https://item.jd.com/2.html\",\"extractedUrl\":\"https://item.jd.com/2.html\",\"platform\":\"JD\"}]";
        String bobDeviceA = "[{\"id\":\"t-bob1\",\"rawContent\":\"https://detail.tmall.com/item.htm?id=3\",\"extractedUrl\":\"https://detail.tmall.com/item.htm?id=3\",\"platform\":\"TB\"}]";

        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-a")
                        .contentType("application/json")
                        .content(aliceDeviceA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("t-a1"));

        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-b")
                        .contentType("application/json")
                        .content(aliceDeviceB))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer bob-secret")
                        .header("X-Device-Id", "device-a")
                        .contentType("application/json")
                        .content(bobDeviceA))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("t-a1"));

        mvc.perform(get("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .queryParam("deviceId", "device-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("t-b1"));

        mvc.perform(get("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer bob-secret")
                        .header("X-Device-Id", "device-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("t-bob1"));

        mvc.perform(get("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer bob-secret")
                        .header("X-Device-Id", "device-unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void fullTaskFieldsFavoritesAndResultsRoundTripIntact() throws Exception {
        String payload = """
                [
                  {
                    "id": "task-1",
                    "rawContent": "https://item.jd.com/100156915831.html",
                    "extractedUrl": "https://item.jd.com/100156915831.html",
                    "platform": "JD",
                    "status": "SUCCESS",
                    "attempts": 1,
                    "maxAttempts": 2,
                    "mimicTaskId": "8899",
                    "submissionGeneration": 2,
                    "terminalServerFailure": false,
                    "serverStatus": "SUCCEEDED",
                    "cancelRequested": false,
                    "executionDeadlineEpochMs": 1763100000000,
                    "progressMessage": "转链成功",
                    "result": {
                      "status": "SUCCESS",
                      "productId": "100156915831",
                      "shortUrl": "https://3.cn/demo",
                      "promotionToken": "! demo ! ZH9112",
                      "promotionUrl": "https://u.jd.com/demo",
                      "productInfo": {
                        "productId": "100156915831",
                        "productName": "测试商品",
                        "price": "99.00",
                        "commissionRate": "10%"
                      }
                    },
                    "error": null,
                    "createdAt": 1763099900000,
                    "completedAt": 1763099950000,
                    "isFavorite": true
                  },
                  {
                    "id": "task-2",
                    "rawContent": "https://detail.tmall.com/item.htm?id=456",
                    "extractedUrl": "https://detail.tmall.com/item.htm?id=456",
                    "platform": "TB",
                    "status": "RUNNING",
                    "mimicTaskId": "8900",
                    "cancelRequested": true,
                    "isFavorite": false
                  }
                ]
                """;

        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "android-123456")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        MvcResult result = mvc.perform(get("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "android-123456"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode data = root.path("data");
        assertEquals(2, data.size());

        JsonNode first = data.get(0);
        assertEquals("task-1", first.path("id").asText());
        assertTrue(first.path("isFavorite").asBoolean());
        assertEquals("8899", first.path("mimicTaskId").asText());
        assertEquals(2, first.path("submissionGeneration").asInt());
        assertEquals("https://3.cn/demo", first.path("result").path("shortUrl").asText());
        assertEquals("测试商品", first.path("result").path("productInfo").path("productName").asText());

        JsonNode second = data.get(1);
        assertEquals("task-2", second.path("id").asText());
        assertEquals("8900", second.path("mimicTaskId").asText());
        assertTrue(second.path("cancelRequested").asBoolean());
        assertFalse(second.path("isFavorite").asBoolean());
    }

    @Test
    void overMaxQueueIsTruncatedTo100AndEmptyArrayClearsQueue() throws Exception {
        ArrayNode items = mapper.createArrayNode();
        for (int i = 0; i < 120; i++) {
            ObjectNode item = mapper.createObjectNode();
            item.put("id", "task-" + i);
            item.put("rawContent", "https://item.jd.com/" + i + ".html");
            item.put("extractedUrl", "https://item.jd.com/" + i + ".html");
            item.put("platform", "JD");
            items.add(item);
        }

        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-limit")
                        .contentType("application/json")
                        .content(items.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(ClientCashbackQueueStore.MAX_QUEUE_SIZE))
                .andExpect(jsonPath("$.data[0].id").value("task-0"))
                .andExpect(jsonPath("$.data[99].id").value("task-99"));

        mvc.perform(get("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-limit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(ClientCashbackQueueStore.MAX_QUEUE_SIZE));

        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-limit")
                        .contentType("application/json")
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-limit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void invalidPayloadAndMissingItemFieldsReturnBadRequest() throws Exception {
        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-a")
                        .contentType("application/json")
                        .content("{\"unexpected\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(put("/api/v1/cashback/queue")
                        .header("Authorization", "Bearer alice-secret")
                        .header("X-Device-Id", "device-a")
                        .contentType("application/json")
                        .content("[{\"id\":\"only-id\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
