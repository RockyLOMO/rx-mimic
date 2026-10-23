package org.rx.crawler.task.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.config.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OrderDeliveryService {
    private final AppConfig appConfig;
    private final OrderStore store;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    @Autowired
    public OrderDeliveryService(AppConfig appConfig, OrderStore store,
                                @Autowired(required = false) ObjectMapper mapper) {
        this.appConfig = appConfig;
        this.store = store;
        this.mapper = mapper != null ? mapper : new ObjectMapper();

        AppConfig.OrderWebhookConfig config = webhookConfig(appConfig);
        int timeoutSeconds = Math.max(1, config.getHttpTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        int threads = Math.max(1, config.getWorkerThreads());
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "order-delivery-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public OrderDeliveryService(AppConfig appConfig, OrderStore store, HttpClient httpClient, ExecutorService executor) {
        this.appConfig = appConfig;
        this.store = store;
        this.mapper = new ObjectMapper();
        AppConfig.OrderWebhookConfig config = webhookConfig(appConfig);
        int timeoutSeconds = Math.max(1, config.getHttpTimeoutSeconds());
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.executor = executor;
    }

    @PostConstruct
    public void init() {
        try {
            Instant now = Instant.now();
            int recovered = store.recoverHangingDeliveries(Timestamp.from(now.minus(Duration.ofMinutes(2))), Timestamp.from(now));
            if (recovered > 0) {
                log.info("Recovered {} hanging order event deliveries on startup", recovered);
            }
        } catch (Exception e) {
            log.warn("Failed to recover hanging order deliveries on startup: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void tickScheduled() {
        tick(Instant.now());
    }

    public void trigger(Instant now) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.submit(() -> dispatchDueDeliveries(now));
    }

    public void tick(Instant now) {
        AppConfig.OrderWebhookConfig config = webhookConfig(appConfig);
        if (!config.isEnabled()) {
            return;
        }
        try {
            int retentionDays = Math.max(1, config.getRetentionDays());
            Timestamp cutoff = Timestamp.from(now.minus(Duration.ofDays(retentionDays)));
            store.cleanExpiredEvents(cutoff);
        } catch (Exception e) {
            log.warn("Clean expired order events failed: {}", e.getMessage());
        }

        try {
            store.recoverHangingDeliveries(Timestamp.from(now.minus(Duration.ofMinutes(2))), Timestamp.from(now));
        } catch (Exception e) {
            log.warn("Recover hanging order deliveries failed: {}", e.getMessage());
        }

        dispatchDueDeliveries(now);
    }

    public void dispatchDueDeliveries(Instant now) {
        try {
            List<OrderEventDeliveryDto> due = store.dueDeliveries(Timestamp.from(now), 50);
            for (OrderEventDeliveryDto delivery : due) {
                if (store.claimDelivery(delivery.getId(), Timestamp.from(now))) {
                    if (executor != null && !executor.isShutdown()) {
                        executor.submit(() -> processDelivery(delivery, now));
                    } else {
                        processDelivery(delivery, now);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Dispatch order deliveries error: {}", e.getMessage());
        }
    }

    public void processDelivery(OrderEventDeliveryDto delivery, Instant now) {
        int nextAttempt = delivery.getAttempts() + 1;
        Instant currentNow = Instant.now();

        AppConfig.OrderSubscriberConfig subscriber = findSubscriber(delivery.getSubscriberId());
        if (subscriber == null) {
            store.updateDeliveryResult(delivery.getId(), OrderCodes.DELIVERY_EXHAUSTED, nextAttempt,
                    null, null, "Subscriber not found in configuration: " + delivery.getSubscriberId(), null, Timestamp.from(currentNow));
            return;
        }

        String targetUrl = delivery.getTargetUrl() != null && !delivery.getTargetUrl().isBlank()
                ? delivery.getTargetUrl() : subscriber.getUrl();

        if (!isValidSubscriberUrl(targetUrl)) {
            store.updateDeliveryResult(delivery.getId(), OrderCodes.DELIVERY_EXHAUSTED, nextAttempt,
                    null, null, "Invalid subscriber URL scheme: " + targetUrl, null, Timestamp.from(currentNow));
            return;
        }

        long timestamp = currentNow.getEpochSecond();
        String secret = subscriber.resolveSecret();
        String signature = computeSignature(delivery.getPayload(), timestamp, secret);

        AppConfig.OrderWebhookConfig config = webhookConfig(appConfig);
        int timeoutSeconds = Math.max(1, config.getHttpTimeoutSeconds());

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("X-Order-Event-Id", String.valueOf(delivery.getEventPublicId()))
                    .header("X-Order-Delivery-Id", String.valueOf(delivery.getId()))
                    .header("X-Order-Timestamp", String.valueOf(timestamp))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            delivery.getPayload() == null ? "" : delivery.getPayload(),
                            StandardCharsets.UTF_8));

            if (signature != null && !signature.isBlank()) {
                reqBuilder.header("X-Order-Signature", signature);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                store.updateDeliveryResult(delivery.getId(), OrderCodes.DELIVERY_SUCCESS, nextAttempt,
                        null, statusCode, null, Timestamp.from(currentNow), Timestamp.from(currentNow));
            } else if (statusCode == 429 || statusCode >= 500) {
                Optional<String> retryAfter = response.headers().firstValue("Retry-After");
                handleFailure(delivery.getId(), nextAttempt, statusCode, "HTTP " + statusCode, currentNow, retryAfter);
            } else {
                store.updateDeliveryResult(delivery.getId(), OrderCodes.DELIVERY_EXHAUSTED, nextAttempt,
                        null, statusCode, "Non-retryable client error: HTTP " + statusCode, null, Timestamp.from(currentNow));
            }
        } catch (HttpTimeoutException e) {
            handleFailure(delivery.getId(), nextAttempt, null, "Timeout: " + e.getMessage(), currentNow, Optional.empty());
        } catch (Exception e) {
            handleFailure(delivery.getId(), nextAttempt, null, "Network error: " + e.getMessage(), currentNow, Optional.empty());
        }
    }

    private void handleFailure(long deliveryId, int nextAttempt, Integer statusCode, String error,
                               Instant currentNow, Optional<String> retryAfterHeader) {
        if (nextAttempt >= 5) {
            store.updateDeliveryResult(deliveryId, OrderCodes.DELIVERY_EXHAUSTED, nextAttempt,
                    null, statusCode, "Max retries reached: " + error, null, Timestamp.from(currentNow));
        } else {
            long delaySeconds = calculateRetryDelaySeconds(nextAttempt, retryAfterHeader);
            Timestamp nextAttemptAt = Timestamp.from(currentNow.plusSeconds(delaySeconds));
            store.updateDeliveryResult(deliveryId, OrderCodes.DELIVERY_RETRY, nextAttempt,
                    nextAttemptAt, statusCode, error, null, Timestamp.from(currentNow));
        }
    }

    private AppConfig.OrderSubscriberConfig findSubscriber(String subscriberId) {
        AppConfig.OrderWebhookConfig config = webhookConfig(appConfig);
        if (config.getSubscribers() == null || subscriberId == null) {
            return null;
        }
        for (AppConfig.OrderSubscriberConfig sub : config.getSubscribers()) {
            if (sub != null && subscriberId.equals(sub.getId())) {
                return sub;
            }
        }
        return null;
    }

    private static AppConfig.OrderWebhookConfig webhookConfig(AppConfig appConfig) {
        if (appConfig == null || appConfig.getCustom() == null || appConfig.getCustom().getOrderSync() == null
                || appConfig.getCustom().getOrderSync().getWebhook() == null) {
            return new AppConfig.OrderWebhookConfig();
        }
        return appConfig.getCustom().getOrderSync().getWebhook();
    }

    public static boolean isValidSubscriberUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.trim().toLowerCase();
        if (lower.startsWith("https://")) {
            return true;
        }
        return lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost");
    }

    public static long calculateRetryDelaySeconds(int currentAttempt, Optional<String> retryAfterHeader) {
        if (retryAfterHeader != null && retryAfterHeader.isPresent()) {
            try {
                long parsed = Long.parseLong(retryAfterHeader.get().trim());
                return Math.min(3600L, Math.max(5L, parsed));
            } catch (NumberFormatException ignored) {}
        }
        return switch (currentAttempt) {
            case 1 -> 60L;       // 1 min
            case 2 -> 300L;      // 5 min
            case 3 -> 900L;      // 15 min
            default -> 3600L;    // 60 min
        };
    }

    public static String computeSignature(String payload, long timestamp, String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            String toSign = timestamp + "." + (payload == null ? "" : payload);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Compute signature error: {}", e.getMessage());
            return "";
        }
    }

    public static boolean verifySignature(String payload, long timestamp, String secret, String expectedHeader) {
        if (expectedHeader == null || expectedHeader.isBlank()) {
            return false;
        }
        String computed = computeSignature(payload, timestamp, secret);
        String cleanComputed = computed.replace("sha256=", "").trim();
        String cleanExpected = expectedHeader.replace("sha256=", "").trim();
        return cleanComputed.equalsIgnoreCase(cleanExpected);
    }
}
