package org.rx.crawler.task.price;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 数字商品主档边界与并发唯一约束；仅使用测试临时 H2 文件。 */
class ProductIdentityStoreTest {
    @TempDir
    Path directory;

    @Test
    void rejectsOpaqueIdsAndKeepsOneProductUnderConcurrentUpserts() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("identity").toString(), null)) {
            PriceWatchStore store = new PriceWatchStore(database);
            store.init();
            store.init();
            Timestamp now = Timestamp.from(Instant.now());
            for (String invalid : new String[]{"encrypted-test-material", "", " 123456789", "123456789 ", "１２３", "1".repeat(65)}) {
                assertThrows(IllegalArgumentException.class,
                        () -> store.upsertProduct(PriceCodes.JD, invalid, "商品", "店", "", now));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> store.upsertProduct(PriceCodes.JD, null, "商品", "店", "", now));
            assertThrows(IllegalArgumentException.class,
                    () -> store.upsertProduct(99, "123456789", "商品", "店", "", now));
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                List<Future<PriceWatchStore.ProductHit>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    results.add(executor.submit(() -> store.upsertProduct(
                            PriceCodes.JD, "123456789", "商品", "店", "", now)));
                }
                long productId = results.get(0).get(10, TimeUnit.SECONDS).getProductId();
                int created = 0;
                for (Future<PriceWatchStore.ProductHit> future : results) {
                    PriceWatchStore.ProductHit hit = future.get(10, TimeUnit.SECONDS);
                    assertEquals(productId, hit.getProductId());
                    if (hit.isCreated()) created++;
                }
                assertEquals(1, created);
                assertEquals("123456789", store.product(productId).getPlatformProductId());
                assertFalse(store.upsertProduct(PriceCodes.JD, "123456789", "新标题", "店", "", now).isCreated());
                assertEquals("新标题", store.findProduct(PriceCodes.JD, "123456789").getTitle());
                assertNotEquals(productId, store.upsertProduct(
                        PriceCodes.TB, "123456789", "另平台商品", "店", "", now).getProductId());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
            database.withConnection(connection -> {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("select platform,resolved_url from price_product order by platform")) {
                    assertTrue(rows.next());
                    assertEquals("https://item.jd.com/123456789.html", rows.getString("resolved_url"));
                    assertTrue(rows.next());
                    assertEquals("https://item.taobao.com/item.htm?id=123456789", rows.getString("resolved_url"));
                    assertFalse(rows.next());
                }
                return null;
            });
        }
    }

    @Test
    void freshSchemaHasNoPersistentAliasOrEncryptedMaterialColumns() throws Exception {
        try (EntityDatabase database = new EntityDatabaseImpl(directory.resolve("schema").toString(), null)) {
            PriceWatchStore store = new PriceWatchStore(database);
            store.init();
            Set<String> forbidden = Set.of("ALIAS_ID", "UNION_PRODUCT_ID", "ENCRYPTED_PRODUCT_ID",
                    "PROMOTION_URL", "RESOLVE_ATTEMPTS", "IDENTITY_STATUS", "ACCOUNT_CONTEXT");
            database.withConnection(connection -> {
                boolean foundNumericIdentity = false;
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "select table_name,column_name,is_nullable from information_schema.columns where table_schema='PUBLIC'")) {
                    while (rows.next()) {
                        String table = rows.getString("table_name");
                        String column = rows.getString("column_name");
                        assertFalse(table.contains("ALIAS"), table);
                        assertFalse(forbidden.contains(column), table + "." + column);
                        if ("PRICE_PRODUCT".equals(table) && "PLATFORM_PRODUCT_ID".equals(column)) {
                            assertEquals("NO", rows.getString("is_nullable"));
                            foundNumericIdentity = true;
                        }
                    }
                }
                assertTrue(foundNumericIdentity);
                return null;
            });
        }
    }
}
