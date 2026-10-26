package org.rx.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.jd.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in live audit. Never changes pages; retains independent card text and geometry. */
@EnabledIfSystemProperty(named = "jd.firstPage.audit", matches = "true")
class JdSearchFirstPageAuditTest {
    @Test void compareReturnedProductsWithFullyScrolledFirstPage() throws Exception {
        var mapper = new ObjectMapper();
        var config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getChrome().setMinIdle(1);
        config.getCustom().getChrome().setCloseBrowserAfterTask(false);
        ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        var mall = config.getCustom().getJdMall();
        mall.setMaxSearchPages(1);
        mall.setPreflightEnabled(false);
        mall.setLoginWaitSeconds(180);
        Path output = Paths.get(System.getProperty("jd.audit.output")).toAbsolutePath();
        Files.createDirectories(output);
        mall.setDebugOutputDir(output.resolve("steps").toString());
        var profiles = new BrowserProfileManager(config);
        var task = new JdMallTask(config, profiles,
                new CrawlEntryService(new BrowserPreflightService()), mapper);
        var request = new JdSearchRequest();
        request.setKeyword("ddr3内存自营");
        request.setProfileName("common");
        request.setMaxResults(100);
        request.setUseCache(false);
        request.setDebugEnabled(true);
        request.setDebugOutputDir(mall.getDebugOutputDir());
        var result = task.search(request);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("returned.json").toFile(), result);
        System.out.println("JD_FIRST_PAGE_STATUS=" + result.getStatus() + " count=" + result.getResultCount()
                + " diagnostics=" + result.getDiagnostics());
        assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus(), result.getMessage());
        Map<String, Object> audit = new LinkedHashMap<>();
        try (var lease = profiles.acquire("common", new WebBrowserConfig())) {
            Browser browser = lease.getBrowser();
            String initialUrl = browser.getCurrentUrl();
            assertEquals("search.jd.com", java.net.URI.create(initialUrl).getHost());
            List<Map<String, Object>> observations = new ArrayList<>();
            browser.scrollPage(0, -100000);
            Thread.sleep(900);
            int stableBottom = 0;
            for (int round = 0; round < 55; round++) {
                Object raw = browser.executeScript(CARDS);
                var cards = mapper.convertValue(raw, new TypeReference<List<Map<String, Object>>>() {});
                observations.addAll(cards);
                boolean bottom = Boolean.TRUE.equals(browser.executeScript(
                        "return window.scrollY+window.innerHeight>=document.documentElement.scrollHeight-10;"));
                stableBottom = bottom ? stableBottom + 1 : 0;
                if (stableBottom >= 3) break;
                browser.scrollPage(0, 650 + (round % 3) * 70);
                Thread.sleep(850);
            }
            String html = browser.executeScript("return document.documentElement.outerHTML;");
            Files.writeString(output.resolve("full-page.html"), html);
            // A snapshot is kept without SKU deduplication: repeated cards remain detectable.
            var finalCards = mapper.convertValue(browser.executeScript(CARDS),
                    new TypeReference<List<Map<String, Object>>>() {});
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("card-observations.json").toFile(), observations);
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("final-cards.json").toFile(), finalCards);
            var ids = finalCards.stream().map(c -> String.valueOf(c.get("skuId"))).toList();
            var returned = result.getItems().stream().map(JdSearchItem::getSkuId).toList();
            List<String> fieldErrors = compareFields(mapper, finalCards, result.getItems());
            var unique = new LinkedHashSet<>(ids);
            var missing = new LinkedHashSet<>(unique); missing.removeAll(returned);
            var extra = new LinkedHashSet<>(returned); extra.removeAll(unique);
            var duplicates = ids.stream().collect(Collectors.groupingBy(i -> i, LinkedHashMap::new, Collectors.counting()));
            duplicates.entrySet().removeIf(e -> e.getValue() < 2);
            audit.put("url", initialUrl);
            audit.put("finalUrl", browser.getCurrentUrl());
            audit.put("domCards", finalCards.size()); audit.put("uniqueSkus", unique.size());
            audit.put("returnedCount", returned.size()); audit.put("missingSkus", missing);
            audit.put("extraSkus", extra); audit.put("duplicateDomSkus", duplicates);
            audit.put("duplicateReturnedCount", returned.size() - new HashSet<>(returned).size());
            audit.put("fieldErrors", fieldErrors);
            audit.put("firstTwoRows", finalCards.subList(0, Math.min(12, finalCards.size())));
            audit.put("lastRow", finalCards.subList(Math.max(0, finalCards.size() - 6), finalCards.size()));
            var rowTops = finalCards.stream().map(c -> ((Number)c.get("top")).doubleValue()).distinct().sorted().toList();
            captureRow(browser, output.resolve("first-row.png"), rowTops.getFirst());
            if (rowTops.size() > 1) captureRow(browser, output.resolve("second-row.png"), rowTops.get(1));
            captureRow(browser, output.resolve("last-row.png"), rowTops.getLast());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("audit.json").toFile(), audit);
            System.out.println("JD_FIRST_PAGE_AUDIT=" + mapper.writeValueAsString(audit));
            assertEquals(initialUrl, browser.getCurrentUrl(), "Audit must remain on page one");
            assertFalse(unique.isEmpty(), "No independent DOM cards found");
            assertTrue(missing.isEmpty(), "Missing first-page SKUs: " + missing);
            assertTrue(extra.isEmpty(), "Unexpected returned SKUs: " + extra);
            assertTrue(fieldErrors.isEmpty(), "Field mismatches: " + fieldErrors);
            assertEquals(returned.size(), new HashSet<>(returned).size(), "Returned duplicate SKU");
        } finally {
            profiles.closeSession("common");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "jd.audit.saved", matches = "true")
    void compareSavedLiveEvidence() throws Exception {
        var mapper = new ObjectMapper();
        Path output = Paths.get(System.getProperty("jd.audit.output"));
        var result = mapper.readValue(output.resolve("returned.json").toFile(), JdSearchResult.class);
        var cards = mapper.readValue(output.resolve("final-cards.json").toFile(), new TypeReference<List<Map<String, Object>>>() {});
        var errors = compareFields(mapper, cards, result.getItems());
        var ids = cards.stream().map(c -> String.valueOf(c.get("skuId"))).toList();
        var returned = result.getItems().stream().map(JdSearchItem::getSkuId).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertEquals(ids, returned, "Entire first-page SKU set and order");
        assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
        var audit = mapper.readValue(output.resolve("audit.json").toFile(), new TypeReference<Map<String, Object>>() {});
        audit.put("fieldErrors", errors);
        audit.put("fieldVerification", "rechecked saved live DOM, including split decimal price nodes");
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("audit-rechecked.json").toFile(), audit);
        assertTrue(errors.isEmpty(), "Field mismatches: " + errors);
        System.out.println("JD_SAVED_FIRST_PAGE_VERIFIED cards=" + cards.size() + " fields=matched");
    }

    private static List<String> compareFields(ObjectMapper mapper, List<Map<String,Object>> finalCards, List<JdSearchItem> items) {
            List<String> fieldErrors = new ArrayList<>();
            Map<String, JdSearchItem> bySku = items.stream().collect(Collectors.toMap(JdSearchItem::getSkuId, i -> i));
            for (var card : finalCards) {
                String sku = String.valueOf(card.get("skuId"));
                var item = bySku.get(sku);
                if (item == null) continue;
                String text = norm(String.valueOf(card.get("text")));
                var titles = (List<String>) card.get("titles");
                if (!titles.stream().anyMatch(t -> norm(t).equals(norm(item.getTitle())))) fieldErrors.add(sku + ":title");
                var price = java.util.regex.Pattern.compile("[¥￥]\\s*(\\d+)(?:\\s*\\.\\s*(\\d+))?").matcher(String.valueOf(card.get("text")));
                String expectedPrice = price.find() ? price.group(1) + (price.group(2) == null ? "" : "." + price.group(2)) : "";
                if (!norm(item.getPrice()).replace("¥", "").replace("￥", "").equals(expectedPrice)) fieldErrors.add(sku + ":price");
                if (item.getShopName() != null && !item.getShopName().isBlank() && !text.contains(norm(item.getShopName()))) fieldErrors.add(sku + ":shop");
                if (!sku.equals(item.getProductId()) || !item.getProductUrl().contains(sku)) fieldErrors.add(sku + ":identity/link");
                var images = (List<Map<String, Object>>) card.get("images");
                if (images.stream().noneMatch(im -> item.getImageUrl().equals(im.get("src")) || item.getImageUrl().equals(im.get("lazy")))) fieldErrors.add(sku + ":image");
                for (String field : List.of("soldCount", "goodRate", "commentCount")) {
                    String value = mapper.valueToTree(item).path(field).asText("");
                    String marker = switch (field) {case "soldCount" -> "已售"; case "goodRate" -> "好评"; default -> "条评价";};
                    var expected = titles.stream().filter(t -> t.contains(marker)).findFirst().orElse("");
                    if (!norm(expected).equals(norm(value))) fieldErrors.add(sku + ":" + field + " expected=" + expected + " got=" + value);
                }
            }
            return fieldErrors;
    }

    private static String norm(String value) { return value == null ? "" : value.replaceAll("\\s+", ""); }

    private static void captureRow(Browser browser, Path file, double top) throws Exception {
        Number scroll = browser.executeScript("return window.scrollY;");
        browser.scrollPage(0, top - 180 - scroll.doubleValue());
        Thread.sleep(800);
        Files.write(file, browser.captureViewportPng());
    }

    private static final String CARDS = """
            const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';};
            return Array.from(document.querySelectorAll('[data-sku]')).filter(e=>visible(e)&&/^\\d+$/.test(e.getAttribute('data-sku'))&&!e.parentElement.closest('[data-sku]')).map(e=>{
              const r=e.getBoundingClientRect();
              return {skuId:e.getAttribute('data-sku'),text:e.innerText,titles:Array.from(e.querySelectorAll('[title]')).map(x=>x.getAttribute('title')),
                links:Array.from(e.querySelectorAll('a[href]')).map(x=>({text:x.innerText,href:x.href})),
                images:Array.from(e.querySelectorAll('img')).map(x=>({src:x.currentSrc||x.src,lazy:x.getAttribute('data-lazy-img')})),
                top:r.top+window.scrollY,left:r.left,width:r.width,height:r.height};
            });
            """;
}
