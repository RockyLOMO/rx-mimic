package org.rx.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.*;
import org.rx.crawler.task.common.*;
import org.rx.crawler.task.jd.*;
import org.rx.crawler.task.tb.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Real logged-in Chrome, serial page operations. No active requests to platform APIs. */
@EnabledIfSystemProperty(named="mall.live.audit", matches="true")
class MallSearchLiveAuditTest {
    @Test void compareSearchWithIndependentPageCards() throws Exception {
        boolean tb = "tb".equals(System.getProperty("mall.audit.platform"));
        int pageCap = Integer.getInteger("mall.audit.pages", 1);
        Path output = Paths.get(System.getProperty("mall.audit.output"));
        Files.createDirectories(output);
        var mapper = new ObjectMapper();
        if (Boolean.getBoolean("mall.audit.saved")) { recheckSaved(output, mapper, tb, pageCap); return; }
        var config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        // Independent page snapshots run inside the pagination boundary and add audit time.
        config.getCustom().setMaxTaskMinutes(10);
        config.getCustom().getChrome().setMinIdle(1);
        config.getCustom().getChrome().setCloseBrowserAfterTask(false);
        ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        var profiles = new BrowserProfileManager(config);
        var recorder = new Recorder(tb, output, mapper);
        profiles.setBrowserFactory((cfg,type) -> {
            WebBrowser actual = new WebBrowser(cfg,type);
            WebBrowser audited = org.mockito.Mockito.mock(WebBrowser.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
            org.mockito.Mockito.doAnswer(call -> {
                String selector = call.getArgument(0);
                boolean wait = call.getArgument(1);
                if (selector.contains("mall-next") && recorder.pages.isEmpty()) {
                    try { recorder.capture(audited, 1); }
                    catch (Exception e) { throw new IllegalStateException("Independent page-one audit failed", e); }
                }
                actual.elementClick(selector, wait);
                return null;
            }).when(audited).elementClick(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
            try { org.mockito.Mockito.doAnswer(call -> {
                String target = call.getArgument(0);
                if (pageCap > 1 && target.matches(".*[?&]page=[2-9][0-9]*.*") && recorder.pages.isEmpty()) {
                    recorder.capture(audited, 1);
                }
                actual.navigateUrl(target, call.getArgument(1), call.getArgument(2));
                return null;
            }).when(audited).navigateUrl(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
            } catch (java.util.concurrent.TimeoutException e) { throw new IllegalStateException(e); }
            return audited;
        });
        JsonNode result;
        try {
            var entry = new CrawlEntryService(new BrowserPreflightService());
            if (tb) {
                var mall = config.getCustom().getTbMall();
                mall.setMaxSearchPages(pageCap); mall.setMaxResultsLimit(200);
                mall.setPreflightEnabled(false); mall.setDebugOutputDir(output.resolve("steps").toString());
                if (Boolean.getBoolean("mall.audit.login")) {
                    mall.setHomeUrl("https://login.taobao.com/member/login.jhtml?redirectURL=https%3A%2F%2Fwww.taobao.com");
                    mall.setLoginWaitSeconds(600);
                }
                var request = new TbSearchRequest();
                request.setKeyword("ddr3内存"); request.setProfileName("common"); request.setMaxResults(200);
                request.setUseCache(false); request.setDebugEnabled(true); request.setDebugOutputDir(mall.getDebugOutputDir());
                result = mapper.valueToTree(new TbMallTask(config, profiles, entry, mapper).search(request));
            } else {
                var mall = config.getCustom().getJdMall();
                mall.setMaxSearchPages(pageCap); mall.setMaxResultsLimit(200);
                mall.setPreflightEnabled(false); mall.setDebugOutputDir(output.resolve("steps").toString());
                var request = new JdSearchRequest();
                request.setKeyword("ddr3内存自营"); request.setProfileName("common"); request.setMaxResults(200);
                request.setUseCache(false); request.setDebugEnabled(true); request.setDebugOutputDir(mall.getDebugOutputDir());
                result = mapper.valueToTree(new JdMallTask(config, profiles, entry, mapper).search(request));
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("returned.json").toFile(), result);
            System.out.println("LIVE_SEARCH_RESULT platform=" + (tb?"tb":"jd") + " status=" + result.path("status")
                    + " count=" + result.path("resultCount") + " pages=" + result.path("diagnostics").path("pagesFetched"));
            assertEquals("SUCCESS", result.path("status").asText(), result.path("message").asText());
            assertEquals(pageCap, result.path("diagnostics").path("pagesFetched").asInt(), "Requested page count must actually be fetched");
            try (var lease = profiles.acquire("common", new WebBrowserConfig())) {
                recorder.capture(lease.getBrowser(), pageCap);
            }
            assertEquals(pageCap, recorder.pages.size(), "Independent DOM evidence for every page");
            var union = new LinkedHashSet<String>();
            var overlap = new LinkedHashSet<String>();
            Map<String,Map<String,Object>> cardsById = new LinkedHashMap<>();
            List<Integer> pageUniqueCounts = new ArrayList<>();
            for (var page : recorder.pages.values()) {
                var pageIds = new LinkedHashSet<String>();
                for (var card : page) {
                    String id = String.valueOf(card.get("id"));
                    pageIds.add(id);
                    cardsById.putIfAbsent(id, card);
                }
                for (String id : pageIds) if (!union.add(id)) overlap.add(id);
                pageUniqueCounts.add(pageIds.size());
            }
            String key = tb ? "itemId" : "skuId";
            List<String> ids = new ArrayList<>();
            List<String> fields = new ArrayList<>();
            for (var item : result.path("items")) {
                String id = item.path(key).asText(); ids.add(id);
                var card = cardsById.get(id); if (card == null) continue;
                String raw = compact(String.valueOf(card.get("text")));
                var titles = (List<String>)card.get("titles");
                String title = compact(item.path("title").asText());
                if (!raw.contains(title) && titles.stream().noneMatch(t -> compact(t).equals(title))) fields.add(id+":title");
                for (String field : tb ? List.of("shopName","soldCount","location") : List.of("shopName","soldCount","goodRate","commentCount")) {
                    String value = compact(item.path(field).asText(""));
                    if (!value.isEmpty() && !raw.contains(value) && titles.stream().noneMatch(t -> compact(t).equals(value))) fields.add(id+":"+field);
                }
                String expected = compact(String.valueOf(card.get("mainPrice"))).replace("¥","").replace("￥","");
                String actual = item.path("price").asText().replace("¥","").replace("￥","");
                if (!expected.equals(actual)) fields.add(id+":price="+actual+" expected="+expected);
                if (!item.path("productUrl").asText().contains(id)) fields.add(id+":productUrl");
                String image = item.path("imageUrl").asText();
                var images = (List<Map<String,Object>>)card.get("images");
                if (image.isBlank() || images.stream().noneMatch(im -> imageKey(image).equals(imageKey(String.valueOf(im.get("src"))))
                        || imageKey(image).equals(imageKey(String.valueOf(im.get("lazy")))))) fields.add(id+":imageUrl");
                if (!id.equals(item.path("productId").asText())) fields.add(id+":productId");
            }
            var missing = new LinkedHashSet<>(union); missing.removeAll(ids);
            var extra = new LinkedHashSet<>(ids); extra.removeAll(union);
            Map<String,Object> audit = new LinkedHashMap<>();
            audit.put("platform",tb?"tb":"jd"); audit.put("pages",pageCap);
            audit.put("pageCardCounts",recorder.pages.values().stream().map(List::size).toList());
            audit.put("pageUniqueCounts",pageUniqueCounts);
            audit.put("uniquePageUnion",union.size()); audit.put("platformRepeatedIds",overlap);
            audit.put("returnedCount",ids.size()); audit.put("returnedDuplicates",ids.size()-new HashSet<>(ids).size());
            audit.put("missingIds",missing); audit.put("extraIds",extra); audit.put("fieldErrors",fields);
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("audit.json").toFile(),audit);
            System.out.println("LIVE_SEARCH_AUDIT="+mapper.writeValueAsString(audit));
            assertTrue(missing.isEmpty(), "Missing page products: "+missing);
            assertTrue(extra.isEmpty(), "Unexpected products: "+extra);
            assertEquals(ids.size(),new HashSet<>(ids).size(),"Returned duplicates");
            assertTrue(fields.isEmpty(),"Field mismatches: "+fields);
        } finally { profiles.closeSession("common"); }
    }

    static String compact(String s) { return s.replaceAll("\\s+",""); }
    static void recheckSaved(Path output, ObjectMapper mapper, boolean tb, int pages) throws Exception {
        JsonNode result = mapper.readTree(output.resolve("returned.json").toFile());
        var expected = new LinkedHashSet<String>();
        Map<String,org.jsoup.nodes.Element> elements = new LinkedHashMap<>();
        for (int page=1; page<=pages; page++) {
            var cards=mapper.readValue(output.resolve("page-"+page+"-cards.json").toFile(),new TypeReference<List<Map<String,Object>>>(){});
            var html=org.jsoup.Jsoup.parse(Files.readString(output.resolve("page-"+page+".html")));
            var pageIds=new LinkedHashSet<String>();
            for(var card:cards) {
                String id=String.valueOf(card.get("id"));
                assertTrue(pageIds.add(id),"Repeated card on page "+page+": "+id);
                expected.add(id);
                var element=html.selectFirst(tb?"a[id=item_id_"+id+"]":"[data-sku="+id+"]");
                assertNotNull(element,"Raw HTML card "+id);
                elements.putIfAbsent(id,element);
            }
        }
        List<String> actual=new ArrayList<>();
        for(var item:result.path("items")) {
            String id=item.path(tb?"itemId":"skuId").asText();actual.add(id);
            var element=elements.get(id); assertNotNull(element,"Unexpected product "+id);
            String price;
            if(tb) {
                var whole=element.selectFirst("[class*=priceInt--]");var fraction=element.selectFirst("[class*=priceFloat--]");
                price=(whole==null?"":whole.text())+(fraction==null?"":fraction.text());
            } else {
                var main=element.selectFirst("[class*=_price_],.p-price");
                price=main==null?"":main.text();
            }
            assertEquals(compact(price).replace("¥","").replace("￥",""),compact(item.path("price").asText()).replace("¥","").replace("￥",""),id+" primary price");
            assertEquals(id,item.path("productId").asText(),id+" product identity");
        }
        assertEquals(new ArrayList<>(expected),actual,"Exact two-page union order and membership");
        assertEquals(actual.size(),new HashSet<>(actual).size());
        var audit=mapper.readValue(output.resolve("audit.json").toFile(),new TypeReference<Map<String,Object>>(){});
        var errors=(List<String>)audit.get("fieldErrors");
        var remaining=errors.stream().filter(e->!e.contains(":price=")).toList();
        assertTrue(remaining.isEmpty(),"Other original field errors: "+remaining);
        audit.put("fieldErrors",remaining);audit.put("priceVerification","all primary price containers matched saved page HTML; subsidy text is separate");
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("audit-rechecked.json").toFile(),audit);
        System.out.println("SAVED_PAGE_AUDIT_OK platform="+(tb?"tb":"jd")+" pages="+pages+" products="+actual.size());
    }
    static String imageKey(String s) {
        if (s == null) return "";
        return s.replaceFirst("^https?:", "").replaceAll("\\?.*$", "")
                .replaceAll("_(?:\\d+x\\d+|q\\d+|sum|webp)[^/]*$", "");
    }
    static class Recorder {
        final boolean tb; final Path output; final ObjectMapper mapper;
        final Map<Integer,List<Map<String,Object>>> pages=new LinkedHashMap<>();
        Recorder(boolean tb,Path output,ObjectMapper mapper) {this.tb=tb;this.output=output;this.mapper=mapper;}
        void capture(Browser browser,int page) throws Exception {
            if(pages.containsKey(page))return;
            String url=browser.getCurrentUrl();
            assertEquals(tb?"s.taobao.com":"search.jd.com",java.net.URI.create(url).getHost());
            browser.scrollPage(0,-100000); Thread.sleep(700);
            int stable=0,old=-1;
            List<Map<String,Object>> cards=List.of();
            for(int i=0;i<60;i++) {
                cards=mapper.convertValue(browser.executeScript(CARDS,tb),new TypeReference<List<Map<String,Object>>>(){});
                Number tail=browser.executeScript("var c=document.querySelectorAll(arguments[0]);return c.length?c[c.length-1].getBoundingClientRect().bottom:99999;",tb?"a[id^='item_id_']":"li[data-sku],div[data-sku]");
                Number height=browser.executeScript("return window.innerHeight;");
                stable=tail.doubleValue()<=height.doubleValue()&&cards.size()==old?stable+1:0;old=cards.size();
                if(stable>=3)break;
                browser.scrollPage(0,600+(i%3)*80);Thread.sleep(750);
            }
            pages.put(page,cards);
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("page-"+page+"-cards.json").toFile(),cards);
            String html=browser.executeScript("return document.documentElement.outerHTML;");
            Files.writeString(output.resolve("page-"+page+".html"),html);
            var rows=cards.stream().map(c->((Number)c.get("top")).doubleValue()).distinct().sorted().toList();
            for(int index:new int[]{0,Math.min(1,rows.size()-1),rows.size()-1}) {
                Number y=browser.executeScript("return window.scrollY;");
                browser.scrollPage(0,rows.get(index)-180-y.doubleValue());Thread.sleep(700);
                Files.write(output.resolve("page-"+page+"-row-"+(index+1)+".png"),browser.captureViewportPng());
            }
            System.out.println("INDEPENDENT_PAGE platform="+(tb?"tb":"jd")+" page="+page+" cards="+cards.size());
        }
    }
    static final String CARDS="""
        const tb=arguments[0],selector=tb?'a[id^="item_id_"]':'[data-sku]';
        return Array.from(document.querySelectorAll(selector)).filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&(tb||!e.parentElement.closest('[data-sku]'));}).map(e=>{
          const r=e.getBoundingClientRect();return {id:tb?e.id.replace('item_id_',''):e.getAttribute('data-sku'),text:e.innerText,
          titles:Array.from(e.querySelectorAll('[title]')).map(x=>x.getAttribute('title')),href:e.href||'',
          images:Array.from(e.querySelectorAll('img')).map(x=>({src:x.currentSrc||x.src,lazy:x.getAttribute('data-lazy-img')})),
          mainPrice:tb?Array.from(e.querySelectorAll('[class*="priceInt--"],[class*="priceFloat--"]')).map(x=>x.innerText).join(''):(e.querySelector('[class*="_price_"],.p-price')?.innerText||''),
          top:r.top+window.scrollY,left:r.left,width:r.width,height:r.height};});
        """;
}
