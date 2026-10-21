package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;
import org.rx.core.Extends;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.tb.TbMallTask;
import org.rx.crawler.task.tb.TbProductDetailRequest;
import org.rx.crawler.task.tb.TbProductDetailResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.rx.util.BeanMapper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 一次性接口探测：被动监听页面自己的请求，不改页面。只在本机手动开启。
 */
public class TbApiDiscoveryTests {
    @Test
    public void discoverTbApis() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.discovery", "false")));
        java.util.List<String> steps = java.util.Arrays.asList(System.getProperty("tb.discovery.steps", "promo,orders,search,detail").split(","));
        String keyword = System.getProperty("tb.discovery.keyword", "抽纸");

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        org.rx.crawler.task.common.ChromeProfilePaths.applyBaseOverride(config.getCustom().getChrome());
        Path out = Paths.get("target", "tb-discovery").toAbsolutePath();
        Files.createDirectories(out);
        Path log = out.resolve("responses.txt");
        Files.write(log, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        config.getCustom().getTbPromotion().setDebugOutputDir(out.resolve("debug").toString());
        config.getCustom().getTbPromotion().setDefaultOutputPath(out.resolve("tb.jsonl").toString());
        config.getCustom().getTbMall().setDebugOutputDir(out.resolve("mall").toString());
        config.getCustom().getTbMall().setDefaultOutputPath(out.resolve("mall.jsonl").toString());

        BrowserProfileManager profileManager = new BrowserProfileManager(config);
        CrawlEntryService entryService = new CrawlEntryService(new BrowserPreflightService());
        ResultWriter writer = new ResultWriter(objectMapper);

        WebBrowserConfig browserConfig = BeanMapper.DEFAULT.map(config.getBrowser(), WebBrowserConfig.class);
        browserConfig.setProfileDataPath(profileManager.resolveProfileDataPath("common"));
        browserConfig.setFingerprintEnabled(true);
        BrowserProfileManager.ProfileLease lease = profileManager.acquire("common", browserConfig);
        WebBrowser web = lease.getBrowser();
        Field contextField = WebBrowser.class.getDeclaredField("context");
        contextField.setAccessible(true);
        BrowserContext context = (BrowserContext) contextField.get(web);
        context.onResponse(r -> record(log, r));
        lease.close();

        if (steps.contains("probe")) {
            BrowserProfileManager.ProfileLease l = profileManager.acquire("common", browserConfig);
            Browser browser = l.getBrowser();
            browser.navigateUrl(config.getCustom().getTbPromotion().getPromotionGoodsUrl(), Browser.BODY_SELECTOR, 60);
            Extends.sleep(6000);
            String script = "var q=arguments[0];var tk=(document.cookie.match(/(?:^|; )_tb_token_=([^;]*)/)||[])[1]||'';" +
                    "var extras={sceneCode:'pub_selection_navigation',floorId:80674,pageNum:0,pageSize:'30',pid:'mm_0_0_0'," +
                    "variableMap:{fn:'search',resultCanBeEmpty:true,q:q,curSelected:{},pubFloorId:80674,sort:'default'," +
                    "tk_navigator:'true',lensScene:'PUB',spmB:'_portal_v2_pages_promo_goods_index_htm'}};" +
                    "var body='t='+Date.now()+'&_tb_token_='+encodeURIComponent(tk)+'&siteCode=selectionPlaza_site&appCode=pub&extras='+encodeURIComponent(JSON.stringify(extras));" +
                    "return fetch('/openapi/param2/1/gateway.unionpub/skyleap.distribution.site.json',{method:'POST',credentials:'include'," +
                    "headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},body:body}).then(function(r){return r.text();});";
            String text = browser.executeScript(script, keyword);
            Files.write(out.resolve("probe-skyleap.json"), String.valueOf(text).getBytes(StandardCharsets.UTF_8));
            java.util.Map<String, Object> card = browser.executeScript("var c=document.querySelector('[data-spm=\"GoodsListItem\"]');" +
                    "if(!c){return null;}c.scrollIntoView({block:'center'});var r=c.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};");
            append(log, "\n#### PROBE card=" + card + "\n");
            Boolean marked = false;
            if (card != null) {
                Extends.sleep(800);
                browser.mouseMove(((Number) card.get("x")).doubleValue(), ((Number) card.get("y")).doubleValue());
                Extends.sleep(1200);
                marked = browser.executeScript("var b=document.querySelector('.good-card-promotion-btn');if(!b){return false;}" +
                        "b.setAttribute('data-rx-probe','1');var r=b.getBoundingClientRect();return r.width>0&&r.height>0;");
            }
            append(log, "\n#### PROBE promote button marked=" + marked + " diag=" + browser.executeScript(
                    "var bs=document.querySelectorAll('.good-card-promotion-btn');var out=[bs.length,location.href];" +
                    "for(var i=0;i<Math.min(3,bs.length);i++){var r=bs[i].getBoundingClientRect();var s=getComputedStyle(bs[i]);out.push([r.left,r.top,r.width,r.height,s.display,s.visibility,s.opacity].join('/'));}" +
                    "out.push(document.querySelectorAll('[data-spm=\"GoodsListItem\"]').length);return out.join(' | ');") + "\n");
            if (Boolean.TRUE.equals(marked)) {
                java.util.Map<String, Object> p = browser.executeScript("var r=document.querySelector('[data-rx-probe=\"1\"]').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};");
                browser.mouseMove(((Number) p.get("x")).doubleValue(), ((Number) p.get("y")).doubleValue());
                Extends.sleep(300);
                browser.mouseClick(((Number) p.get("x")).doubleValue(), ((Number) p.get("y")).doubleValue());
                Extends.sleep(9000);
                Files.write(out.resolve("probe-dialog.html"), String.valueOf((Object) browser.executeScript(
                        "return document.documentElement.outerHTML;")).getBytes(StandardCharsets.UTF_8));
            }
            l.close();
        }
        if (steps.contains("link")) {
            BrowserProfileManager.ProfileLease l = profileManager.acquire("common", browserConfig);
            Browser browser = l.getBrowser();
            browser.navigateUrl(config.getCustom().getTbPromotion().getPromotionGoodsUrl(), Browser.BODY_SELECTOR, 60);
            Extends.sleep(5000);
            String get = "var path=arguments[0],qs=arguments[1]||'';var tk=(document.cookie.match(/(?:^|; )_tb_token_=([^;]*)/)||[])[1]||'';" +
                    "return fetch(path+'?t='+Date.now()+'&_tb_token_='+encodeURIComponent(tk)+(qs?'&'+qs:''),{credentials:'include'}).then(function(r){return r.text();});";
            String zones = browser.executeScript(get, "/openapi/param2/1/gateway.unionpub/record.adzone.queryForPublish.json", "");
            Files.write(out.resolve("probe-zones.json"), String.valueOf(zones).getBytes(StandardCharsets.UTF_8));
            String def = browser.executeScript(get, "/openapi/param2/1/gateway.unionpub/record.adzone.defaultAdzone.json", "");
            Files.write(out.resolve("probe-default-zone.json"), String.valueOf(def).getBytes(StandardCharsets.UTF_8));
            String mktId = System.getProperty("tb.discovery.mktId");
            String pid = System.getProperty("tb.discovery.pid");
            if (mktId != null && pid != null) {
                String vm = "{\"itemId\":\"" + mktId + "\",\"pubFloorId\":80674}";
                String link = browser.executeScript(get, "/openapi/param2/1/gateway.unionpub/xt.entry.json",
                        "floorId=124176&refpid=" + java.net.URLEncoder.encode(pid, "UTF-8")
                                + "&variableMap=" + java.net.URLEncoder.encode(vm, "UTF-8"));
                Files.write(out.resolve("probe-link.json"), String.valueOf(link).getBytes(StandardCharsets.UTF_8));
            }
            l.close();
        }
        if (steps.contains("promo")) {
            TbPromotionUrlTask task = new TbPromotionUrlTask(config, profileManager, entryService, writer, objectMapper);
            PromotionUrlRequest request = new PromotionUrlRequest();
            request.setKeyword(keyword);
            request.setAdSiteName("0");
            request.setProfileName("common");
            request.setDebugEnabled(true);
            PromotionUrlResult result = task.getPromotionUrl(request);
            append(log, "\n#### PROMO status=" + result.getStatus() + " msg=" + result.getMessage() + "\n");
        }
        if (steps.contains("orders")) {
            TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, profileManager, entryService, writer, objectMapper);
            TbPromotionOrdersRequest request = new TbPromotionOrdersRequest();
            request.setStartTime(LocalDate.now().minusDays(30).toString());
            request.setEndTime(LocalDate.now().toString());
            request.setProfileName("common");
            TbPromotionOrdersResult result = task.getPromotionOrders(request);
            append(log, "\n#### ORDERS status=" + result.getStatus() + " count="
                    + (result.getOrders() == null ? -1 : result.getOrders().size()) + " msg=" + result.getMessage() + "\n");
        }
        if (steps.contains("search")) {
            BrowserProfileManager.ProfileLease l = profileManager.acquire("common", browserConfig);
            Browser browser = l.getBrowser();
            browser.navigateUrl("https://www.taobao.com", Browser.BODY_SELECTOR, 60);
            Extends.sleep(3000);
            browser.navigateUrl("https://s.taobao.com/search?q=" + java.net.URLEncoder.encode(keyword, "UTF-8"),
                    Browser.BODY_SELECTOR, 60);
            Extends.sleep(8000);
            browser.scrollPage(0, 1200);
            Extends.sleep(3000);
            append(log, "\n#### SEARCH url=" + browser.getCurrentUrl() + " mtop=" + browser.executeScript(
                    "return !!(window.lib&&window.lib.mtop&&window.lib.mtop.request)+'/'+(window.lib&&window.lib.mtop&&window.lib.mtop.config&&window.lib.mtop.config.prefix);") + "\n");
            Files.write(out.resolve("search.html"), String.valueOf((Object) browser.executeScript(
                    "return document.documentElement.outerHTML;")).getBytes(StandardCharsets.UTF_8));
            l.close();
        }
        if (steps.contains("detailprobe")) {
            BrowserProfileManager.ProfileLease l = profileManager.acquire("common", browserConfig);
            Browser browser = l.getBrowser();
            String itemId = System.getProperty("tb.discovery.itemId", "538344656375");
            browser.navigateUrl("https://item.taobao.com/item.htm?id=" + itemId, Browser.BODY_SELECTOR, 60);
            Extends.sleep(5000);
            append(log, "\n#### DETAILPROBE url=" + browser.getCurrentUrl() + " mtop=" + browser.executeScript(
                    "return !!(window.lib&&window.lib.mtop&&window.lib.mtop.request)+'/'+(window.lib&&window.lib.mtop&&window.lib.mtop.config&&window.lib.mtop.config.prefix);") + "\n");
            java.util.Map<String, Object> sc = browser.executeScript("var best=null,o=[];document.querySelectorAll('*').forEach(function(e){var s=getComputedStyle(e);" +
                    "if((s.overflowY==='auto'||s.overflowY==='scroll')&&e.scrollHeight>e.clientHeight+50){var r=e.getBoundingClientRect();" +
                    "o.push(e.tagName+'.'+String(e.className).slice(0,50)+' sh='+e.scrollHeight+' ch='+e.clientHeight+' rect='+[r.left,r.top,r.width,r.height].map(Math.round).join(','));" +
                    "if(r.width>300&&(!best||e.scrollHeight>best.scrollHeight)){best=e;}}});" +
                    "if(!best){return {list:o.join('\\n')};}var b=best.getBoundingClientRect();return {list:o.join('\\n'),x:b.left+b.width*0.5,y:b.top+Math.min(b.height*0.6,600)};");
            append(log, "\n#### DETAILPROBE scrollables=" + sc.get("list") + "\n");
            double sx = sc.get("x") == null ? 40 : ((Number) sc.get("x")).doubleValue();
            double sy = sc.get("y") == null ? 620 : ((Number) sc.get("y")).doubleValue();
            StringBuilder ys = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                browser.mouseMove(sx, sy);
                browser.scrollPage(0, 650 + (i % 3) * 90);
                Extends.sleep(1300);
                ys.append(browser.executeScript("var m=0;document.querySelectorAll('*').forEach(function(e){if(e.scrollTop>m){m=e.scrollTop;}});return Math.round(window.scrollY)+'/'+Math.round(m);").toString()).append(' ');
            }
            append(log, "\n#### DETAILPROBE scroll=" + ys + "\n");
            Files.write(out.resolve("detail-body.png"), browser.screenshotAsBytes("body"));
            Extends.sleep(3000);
            Files.write(out.resolve("detail-scrolled.html"), String.valueOf((Object) browser.executeScript(
                    "return document.documentElement.outerHTML;")).getBytes(StandardCharsets.UTF_8));
            append(log, "\n#### DETAILPROBE dom=" + browser.executeScript(
                    "var o=[];document.querySelectorAll('[class*=\"param\" i],[class*=\"attr\" i],[class*=\"desc\" i],[id*=\"desc\" i]').forEach(function(e){" +
                            "if(o.length<40){o.push(e.tagName+'.'+String(e.className).slice(0,60)+'#'+e.id+' imgs='+e.querySelectorAll('img').length+' txt='+(e.innerText||'').slice(0,60).replace(/\\s+/g,' '));}});" +
                            "o.push('iframes='+document.querySelectorAll('iframe').length);" +
                            "var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);var n;while((n=w.nextNode())){var tx=n.nodeValue.trim();" +
                            "if(tx==='参数信息'||tx==='图文详情'||tx==='品牌'){var p=n.parentElement,chain=[];for(var k=0;k<6&&p;k++){chain.push(p.tagName+'.'+String(p.className).slice(0,40));p=p.parentElement;}o.push(tx+' <- '+chain.join(' < '));}}" +
                            "return o.join('\\n');") + "\n");
            l.close();
        }
        if (steps.contains("detail")) {
            TbMallTask task = new TbMallTask(config, profileManager, entryService, writer, objectMapper);
            TbProductDetailRequest request = new TbProductDetailRequest();
            request.setItemId(System.getProperty("tb.discovery.itemId", "601440653278"));
            request.setProfileName("common");
            request.setDebugEnabled(true);
            TbProductDetailResult result = task.productDetail(request);
            append(log, "\n#### DETAIL status=" + result.getStatus() + " msg=" + result.getMessage()
                    + " title=" + result.getTitle() + "\n");
        }
        Extends.sleep(1500);
    }

    private static void record(Path log, Response r) {
        try {
            String type = r.request().resourceType();
            String url = r.url();
            if ("image".equals(type) || "stylesheet".equals(type) || "font".equals(type) || "media".equals(type)) {
                return;
            }
            if (url.contains("mo.m.taobao.com") || url.contains("g.alicdn.com") || url.contains("log.mmstat")
                    || url.contains("umdc") || url.contains("aplus")) {
                return;
            }
            if (!(url.contains("alimama") || url.contains("mtop") || url.contains("taobao.com") || url.contains("tmall.com"))) {
                append(log, "\n--- " + type + " " + url.substring(0, Math.min(160, url.length())) + "\n");
                return;
            }
            String body = "";
            try {
                body = r.text();
            } catch (Exception ignored) {
            }
            if (url.contains("skyleap") || url.contains("getTbkOrderDetails") || url.contains("relationrecommend")
                    || url.contains("pcdetail") || url.contains("detail.getdetail") || url.contains("common.get.code")
                    || url.contains("promotion") || url.contains("shareitem") || url.contains("desc")
                    || url.contains("param") || url.contains("detail")) {
                String name = url.replaceAll("^https?://[^/]+/", "").replaceAll("[^a-zA-Z0-9.]+", "_");
                name = name.substring(0, Math.min(80, name.length()));
                Files.write(log.getParent().resolve("full-" + System.nanoTime() + "-" + name + ".txt"),
                        body.getBytes(StandardCharsets.UTF_8));
            }
            if (body.length() > 4000) {
                body = body.substring(0, 4000);
            }
            String safeUrl = url.replaceAll("(_tb_token_|sign|token|h5st|x-sign|umidToken|ua|bx-ua|_m_h5_tk)=[^&]*", "$1=***");
            String post = r.request().postData();
            if (post != null && post.length() > 1500) {
                post = post.substring(0, 1500);
            }
            append(log, "\n=== " + r.request().method() + " " + r.status() + " " + type + " " + safeUrl + "\n"
                    + (post == null ? "" : "POST: " + post.replaceAll("(_tb_token_|token)=[^&]*", "$1=***") + "\n")
                    + body + "\n");
        } catch (Exception ignored) {
        }
    }

    private static synchronized void append(Path log, String text) {
        try {
            Files.write(log, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
