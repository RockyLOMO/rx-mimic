package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TbUnionPageApiTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pickAdzoneUsesConfiguredPidWhenNameMisses() throws Exception {
        String pid = "mm_10299978403_3442650231_116337200060";
        String json = "[{\"siteTypes\":[{\"sites\":[{\"siteName\":\"RockyLOMO\",\"adzones\":["
                + "{\"adzoneName\":\"0\",\"pid\":\"" + pid + "\"},"
                + "{\"adzoneName\":\"9\",\"pid\":\"mm_1_2_9\"}]}]}]}]";
        JsonNode data = mapper.readTree(json);
        assertEquals(pid, TbUnionPageApi.pickAdzone(data, "RockyLOMO", "0", pid).path("pid").asText());
        assertEquals(pid, TbUnionPageApi.pickAdzone(data, "RockyLOMO", "missing", pid).path("pid").asText());
        assertNull(TbUnionPageApi.pickAdzone(data, "RockyLOMO", "1", ""));
    }

    @Test
    void resolveZoneFallsBackToConfiguredPid() throws Exception {
        String pid = "mm_10299978403_3442650231_116333200319";
        JsonNode zone = TbUnionPageApi.resolveZone(mapper.readTree("[]"), mapper.readTree("null"),
                "RockyLOMO", "1", pid, mapper);
        assertEquals(pid, zone.path("pid").asText());
        assertEquals("1", zone.path("adzoneName").asText());
        assertTrue(TbUnionPageApi.isTbPid(pid));
        assertFalse(TbUnionPageApi.isTbPid("5"));
        assertNull(TbUnionPageApi.resolveZone(mapper.readTree("[]"), null, "RockyLOMO", "1", "5", mapper));
    }
}
