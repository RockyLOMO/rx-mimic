package org.rx.crawler.task.order;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OrderDeliveryServiceTest {

    @Test
    void testUrlValidation() {
        assertTrue(OrderDeliveryService.isValidSubscriberUrl("https://webhook.example.com/orders"));
        assertTrue(OrderDeliveryService.isValidSubscriberUrl("http://127.0.0.1:8080/webhook"));
        assertTrue(OrderDeliveryService.isValidSubscriberUrl("http://localhost:8080/webhook"));

        // Plain HTTP non-localhost should be rejected
        assertFalse(OrderDeliveryService.isValidSubscriberUrl("http://evil.com/webhook"));
        assertFalse(OrderDeliveryService.isValidSubscriberUrl(null));
        assertFalse(OrderDeliveryService.isValidSubscriberUrl(""));
    }

    @Test
    void testRetryDelayCalculation() {
        assertEquals(60L, OrderDeliveryService.calculateRetryDelaySeconds(1, Optional.empty()));
        assertEquals(300L, OrderDeliveryService.calculateRetryDelaySeconds(2, Optional.empty()));
        assertEquals(900L, OrderDeliveryService.calculateRetryDelaySeconds(3, Optional.empty()));
        assertEquals(3600L, OrderDeliveryService.calculateRetryDelaySeconds(4, Optional.empty()));

        // Retry-After header overrides default schedule
        assertEquals(120L, OrderDeliveryService.calculateRetryDelaySeconds(1, Optional.of("120")));
        assertEquals(5L, OrderDeliveryService.calculateRetryDelaySeconds(1, Optional.of("1"))); // Min 5s
        assertEquals(3600L, OrderDeliveryService.calculateRetryDelaySeconds(1, Optional.of("999999"))); // Max 3600s
    }

    @Test
    void testComputeSignature() {
        String secret = "test-secret";
        String payload = "{\"event\":\"order.created\"}";
        long timestamp = 1700000000L;

        String sig1 = OrderDeliveryService.computeSignature(payload, timestamp, secret);
        assertNotNull(sig1);
        assertFalse(sig1.isBlank());

        // Same inputs produce identical HMAC-SHA256 signature
        String sig2 = OrderDeliveryService.computeSignature(payload, timestamp, secret);
        assertEquals(sig1, sig2);

        // Different payload produces different signature
        String sig3 = OrderDeliveryService.computeSignature("{\"event\":\"different\"}", timestamp, secret);
        assertNotEquals(sig1, sig3);

        // Empty secret produces empty string
        assertEquals("", OrderDeliveryService.computeSignature(payload, timestamp, ""));
        assertEquals("", OrderDeliveryService.computeSignature(payload, timestamp, null));
    }
}
