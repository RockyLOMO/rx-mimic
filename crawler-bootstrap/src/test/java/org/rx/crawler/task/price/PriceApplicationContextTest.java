package org.rx.crawler.task.price;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.Application;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.fiddler-enabled=false", "app.custom.chrome.preWarm=false", "app.custom.remotingEnabled=false",
                "app.custom.priceWatch.enabled=false", "app.custom.loginKeepAlive.enabled=false"})
@DirtiesContext
class PriceApplicationContextTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) {
        registry.add("app.baseDir", () -> temp.toString());
        registry.add("app.storage.h2DbPath", () -> temp.resolve("tasks").toString());
        registry.add("app.storage.priceDbPath", () -> temp.resolve("numeric-prices").toString());
    }
    @Autowired PriceWatchStore prices;
    @Autowired CustomCrawlQueueService queue;
    @Autowired UnionMaterialBoundary materials;

    @Test void startsWithSeparateNumericPriceDatabaseAndNoBeanCycle() {
        assertTrue(queue.isDatabaseAvailable());
        var product = prices.upsertProduct(PriceCodes.JD, "123456789", "sample", "shop", "",
                java.sql.Timestamp.from(java.time.Instant.now()));
        assertEquals("123456789", prices.product(product.getProductId()).getPlatformProductId());
        assertNotNull(materials);
    }
}
