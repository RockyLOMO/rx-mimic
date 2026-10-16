package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.exception.InvalidException;
import org.rx.util.BeanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.rx.core.Extends.tryClose;

/**
 * 淘宝/天猫商城商品详情（tbProductDetail）抓取任务。
 * 支持按 itemId 或 productUrl 提取商品标题、价格、店铺名、规格参数、主图相册及详情长图，
 * 并支持将图片下载到本地。
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class TbMallTask implements CustomCrawlTask<TbProductDetailRequest, TbProductDetailResult> {
    public static final String DETAIL_TASK_TYPE = "tbProductDetail";
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final Pattern ITEM_ID_PATTERN = Pattern.compile("[?&]id=(\\d+)");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\d+$");

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final SliderVerifyHandler sliderVerifyHandler = new SliderVerifyHandler();
    private final ThreadLocal<Long> taskDeadlineHolder = new ThreadLocal<Long>();

    @Override
    public String taskType() {
        return DETAIL_TASK_TYPE;
    }

    @Override
    public TbProductDetailResult execute(TbProductDetailRequest request) {
        return productDetail(request);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    public TbProductDetailResult productDetail(TbProductDetailRequest rawRequest) {
        TbMallConfig config = appConfig.getCustom().getTbMall();
        TbProductDetailRequest request = normalizeDetailRequest(rawRequest, config);
        TbProductDetailResult result = createDetailResult(request);
        DebugRecorder debug = new DebugRecorder(request.getItemId(), request.getProfileName(),
                Boolean.TRUE.equals(request.getDebugEnabled()), request.getDebugOutputDir(), config.getDebugOutputDir());
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }

        BrowserProfileManager.ProfileLease lease = null;
        int deadlineMinutes = Math.max(1, appConfig.getCustom().getMaxTaskMinutes())
                * (Boolean.TRUE.equals(request.getDownloadImages()) ? 2 : 1);
        taskDeadlineHolder.set(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(deadlineMinutes));

        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(config));
            Browser browser = lease.getBrowser();

            CrawlEntryOptions options = createEntryOptions(request, config);
            humanNavigationPause(config);

            CrawlEntryResult entry = entryService.enter(browser, lease, options);
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");

            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }

            // 检查是否跳转到登录页，需要人工扫码登录
            if (isLoginRequiredPage(browser, config)) {
                result.setLoginRequired(true);
                if (options.isKeepBrowserOpenOnLoginRequired()
                        && entryService.waitLoginCompleted(browser, options, entry)) {
                    result.setLoginRequired(false);
                    result.setCurrentUrl(browser.getCurrentUrl());
                } else {
                    fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                            "TB login wait timeout after " + options.getLoginWaitSeconds() + "s. Task stopped.");
                    debug.snapshot(browser, "01-login-timeout");
                    return result;
                }
            }

            runDetailFlow(browser, lease, request, config, options, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("TB mall product detail fail, itemId={}, profile={}, error={}",
                    request.getItemId(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private void runDetailFlow(Browser browser, BrowserProfileManager.ProfileLease lease,
            TbProductDetailRequest request, TbMallConfig config, CrawlEntryOptions options,
            TbProductDetailResult result, DebugRecorder debug) throws TimeoutException {
        // 滑块验证检测与自动处理
        if (sliderVerifyHandler.isSliderVerifyPage(browser)) {
            debug.snapshot(browser, "02-slider-detected");
            boolean passed = sliderVerifyHandler.checkAndHandle(browser, "02-product-slider",
                    config.getSliderMaxAutoAttempts(), config.getStepDelayMillis(),
                    (b, name) -> debug.snapshot(b, name));
            if (!passed) {
                fail(result, CustomCrawlStatus.FAILED, "TB slider verification not cleared on product page");
                debug.snapshot(browser, "02-slider-failed");
                return;
            }
        }

        if (!waitItemPageReady(browser, config, options, result, debug)) {
            return;
        }

        Extends.sleep(600 + ThreadLocalRandom.current().nextInt(600));

        // 提取基础商品信息
        Map<String, Object> base = readProductBase(browser);
        result.setTitle(asString(base.get("title")));
        result.setPrice(asString(base.get("price")));
        result.setShopName(asString(base.get("shopName")));
        List<String> mainImageUrls = asStringList(base.get("mainImages"));
        debug.snapshot(browser, "03-item-base-collected");

        // 滚动页面加载详情图（描述区域）
        List<String> detailImageUrls = scrollDetailAndCollect(browser, config);
        debug.snapshot(browser, "04-detail-scrolled");

        // 提取规格参数
        if (Boolean.TRUE.equals(request.getIncludeSpecs())) {
            List<TbProductSpecItem> specs = readProductSpecs(browser);
            result.setSpecs(specs);
            debug.snapshotText("05-specs-collected", "specsCount=" + specs.size());
        }

        // 排除与主图重复的 URL 并去重
        detailImageUrls = dedupeDetailImages(detailImageUrls, mainImageUrls, config.getMaxDetailImages());

        if (Strings.isEmpty(result.getTitle()) && mainImageUrls.isEmpty() && detailImageUrls.isEmpty()) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB item page structure not recognized");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-item-structure-unknown");
            return;
        }

        result.setCurrentUrl(browser.getCurrentUrl());

        // 处理图片下载或纯 URL 封装
        if (Boolean.TRUE.equals(request.getDownloadImages())) {
            result.setMainImages(downloadImages(browser, request.getItemId(), mainImageUrls, "main", config, result.getDiagnostics()));
            result.setDetailImages(downloadImages(browser, request.getItemId(), detailImageUrls, "detail", config, result.getDiagnostics()));
        } else {
            result.setMainImages(toImageInfos(mainImageUrls));
            result.setDetailImages(toImageInfos(detailImageUrls));
        }

        debug.snapshotText("06-images-collected", "mainImages=" + result.getMainImages().size()
                + ", detailImages=" + result.getDetailImages().size());
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("Product detail captured, mainImages=" + result.getMainImages().size()
                + ", detailImages=" + result.getDetailImages().size());
    }

    private boolean waitItemPageReady(Browser browser, TbMallConfig config, CrawlEntryOptions options,
            TbProductDetailResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + config.getPageTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("waitItemPageReady");
            if (isLoginRequiredPage(browser, config)) {
                result.setLoginRequired(true);
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED, "TB item page requires login");
                debug.snapshot(browser, "02-item-login-required");
                return false;
            }
            if (sliderVerifyHandler.isSliderVerifyPage(browser)) {
                sliderVerifyHandler.checkAndHandle(browser, "02-item-wait-slider",
                        config.getSliderMaxAutoAttempts(), config.getStepDelayMillis(),
                        (b, name) -> debug.snapshot(b, name));
            }

            Boolean ready = browser.executeScript(
                    "function has(s){return !!document.querySelector(s);}" +
                    "return has('[class*=\"mainTitle--\"]') || has('.tb-main-title') || has('[class*=\"ItemHeader\"]') || has('meta[property=\"og:title\"]') || has('[class*=\"priceText--\"]') || has('.tb-rmb-num');"
            );
            if (Boolean.TRUE.equals(ready)) {
                return true;
            }
            Extends.sleep(400);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readProductBase(Browser browser) {
        String script = "return (function() {" +
                "function text(el){return el?(el.innerText||el.textContent||'').trim():'';}" +
                "function attr(el, name){return el?(el.getAttribute(name)||'').trim():'';}" +
                "var title = '';" +
                "var titleEl = document.querySelector('[class*=\"mainTitle--\"], .tb-main-title, [class*=\"ItemHeader\"] h1, h1[data-title], meta[property=\"og:title\"]');" +
                "if(titleEl){ title = titleEl.getAttribute('content') || text(titleEl); }" +
                "if(!title){ var h1 = document.querySelector('h1'); if(h1) title = text(h1); }" +
                "if(!title){ title = document.title.replace(/-(淘宝网|天猫|Tmall\\.com).*/i, '').trim(); }" +
                "var price = '';" +
                "var priceEl = document.querySelector('[class*=\"Price--priceText--\"], [class*=\"priceText--\"], .tb-rmb-num, .tm-price, [class*=\"priceWrap\"]');" +
                "if(priceEl){ price = text(priceEl); }" +
                "if(!price){ var match = (document.body?document.body.innerText:'').match(/¥\\s*(\\d+(?:\\.\\d{1,2})?)/); if(match) price = match[1]; }" +
                "var shopName = '';" +
                "var shopEl = document.querySelector('[class*=\"ShopHeader--title--\"], [class*=\"shopName--\"], .tb-shop-name, .shop-name, a[class*=\"shop-name\"], [class*=\"shopHeader\"] a');" +
                "if(shopEl){ shopName = text(shopEl); }" +
                "if(!shopName){ var shopMeta = document.querySelector('meta[name=\"micro-shop:title\"]'); if(shopMeta) shopName = attr(shopMeta, 'content'); }" +
                "var mainImages = [];" +
                "var imgEls = document.querySelectorAll('[class*=\"PicGallery--thumbnails--\"] img, [class*=\"PicGallery--mainPic--\"] img, #J_UlThumb img, #J_ImgBooth');" +
                "for(var i=0; i<imgEls.length; i++){" +
                "  var src = attr(imgEls[i], 'src') || attr(imgEls[i], 'data-src');" +
                "  if(src && (src.indexOf('alicdn.com')>=0 || src.indexOf('tbcdn.cn')>=0)){" +
                "    if(src.indexOf('//') === 0) src = 'https:' + src;" +
                "    src = src.replace(/_\\d+x\\d+.*\\.jpg.*/i, '');" +
                "    if(mainImages.indexOf(src) < 0) mainImages.push(src);" +
                "  }" +
                "}" +
                "return { title: title, price: price, shopName: shopName, mainImages: mainImages };" +
                "})();";
        Map<String, Object> result = browser.executeScript(script);
        return result != null ? result : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<TbProductSpecItem> readProductSpecs(Browser browser) {
        String script = "return (function() {" +
                "function text(el){return el?(el.innerText||el.textContent||'').trim():'';}" +
                "var specs = [];" +
                "var items = document.querySelectorAll('[class*=\"ItemAttributes--attrList--\"] [class*=\"attrItem--\"], [class*=\"Attributes--list--\"] li, #attributes li, .attributes-list li');" +
                "for(var i=0; i<items.length; i++){" +
                "  var t = text(items[i]);" +
                "  if(!t) continue;" +
                "  var idx = t.indexOf(':') >= 0 ? t.indexOf(':') : t.indexOf('：');" +
                "  if(idx > 0){" +
                "    var name = t.substring(0, idx).trim();" +
                "    var val = t.substring(idx + 1).trim();" +
                "    if(name && val){ specs.push({ name: name, value: val }); }" +
                "  }" +
                "}" +
                "return specs;" +
                "})();";
        List<Map<String, Object>> rawList = browser.executeScript(script);
        List<TbProductSpecItem> specs = new ArrayList<TbProductSpecItem>();
        if (rawList != null) {
            for (Map<String, Object> map : rawList) {
                TbProductSpecItem item = new TbProductSpecItem();
                item.setName(asString(map.get("name")));
                item.setValue(asString(map.get("value")));
                specs.add(item);
            }
        }
        return specs;
    }

    @SuppressWarnings("unchecked")
    private List<String> scrollDetailAndCollect(Browser browser, TbMallConfig config) throws TimeoutException {
        List<String> allDetailImages = new ArrayList<String>();
        int steps = config.getDetailScrollSteps();
        for (int i = 0; i < steps; i++) {
            ensureTaskDeadline("scrollDetail");
            browser.executeScript("window.scrollBy(0, Math.floor(window.innerHeight * 0.85));");
            Extends.sleep(Math.max(250, config.nextStepDelayMillis() / 2));
            List<String> batch = readDetailImagesNow(browser);
            allDetailImages = mergeUniqueUrls(allDetailImages, batch);

            Boolean atBottom = browser.executeScript(
                    "return (window.innerHeight + window.pageYOffset) >= (document.body.offsetHeight - 50);"
            );
            if (Boolean.TRUE.equals(atBottom)) {
                break;
            }
        }
        return allDetailImages;
    }

    @SuppressWarnings("unchecked")
    private List<String> readDetailImagesNow(Browser browser) {
        String script = "return (function() {" +
                "function attr(el, name){return el?(el.getAttribute(name)||'').trim():'';}" +
                "var images = [];" +
                "var descEl = document.querySelector('#description, [class*=\"Desc--desc--\"], [class*=\"desc-root\"], [id*=\"desc\"]');" +
                "var scope = descEl || document.body;" +
                "var imgEls = scope.querySelectorAll('img');" +
                "for(var i=0; i<imgEls.length; i++){" +
                "  var src = attr(imgEls[i], 'src') || attr(imgEls[i], 'data-src') || attr(imgEls[i], 'data-original');" +
                "  if(src && (src.indexOf('alicdn.com')>=0 || src.indexOf('tbcdn.cn')>=0)){" +
                "    if(src.indexOf('//') === 0) src = 'https:' + src;" +
                "    src = src.replace(/_\\d+x\\d+.*\\.jpg.*/i, '');" +
                "    if(images.indexOf(src) < 0) images.push(src);" +
                "  }" +
                "}" +
                "return images;" +
                "})();";
        List<String> list = browser.executeScript(script);
        return list != null ? list : Collections.emptyList();
    }

    private List<TbImageInfo> downloadImages(Browser browser, String itemId, List<String> urls,
            String subDir, TbMallConfig config, Map<String, Object> diagnostics) throws TimeoutException {
        List<TbImageInfo> out = new ArrayList<TbImageInfo>();
        if (urls == null || urls.isEmpty()) {
            return out;
        }
        Path dir = Paths.get(config.getImageSaveDir(), itemId, subDir);
        int index = 0;
        for (String url : urls) {
            index++;
            ensureTaskDeadline("image-download");
            try {
                byte[] bytes = browser.downloadAsBytes(url);
                if (bytes == null || bytes.length == 0) {
                    throw new InvalidException("Empty image body: %s", url);
                }
                Files.createDirectories(dir);
                String fileName = String.format("%03d%s", index, imageExtension(url));
                Path file = dir.resolve(fileName);
                Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                TbImageInfo info = new TbImageInfo();
                info.setUrl(url);
                info.setLocalPath(file.toString());
                info.setFileName(fileName);
                out.add(info);
            } catch (Exception e) {
                log.warn("Download TB image fail, url={}, error={}", url, e.getMessage());
            }
            if (index < urls.size()) {
                Extends.sleep(config.getDownloadDelayMillis());
            }
        }
        return out;
    }

    private List<TbImageInfo> toImageInfos(List<String> urls) {
        List<TbImageInfo> list = new ArrayList<TbImageInfo>();
        if (urls == null) {
            return list;
        }
        for (String u : urls) {
            TbImageInfo info = new TbImageInfo();
            info.setUrl(u);
            list.add(info);
        }
        return list;
    }

    private List<String> dedupeDetailImages(List<String> detailUrls, List<String> mainUrls, int maxCount) {
        List<String> result = new ArrayList<String>();
        for (String u : detailUrls) {
            if (mainUrls.contains(u) || result.contains(u)) {
                continue;
            }
            result.add(u);
            if (result.size() >= maxCount) {
                break;
            }
        }
        return result;
    }

    private List<String> mergeUniqueUrls(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<String>(first);
        if (second != null) {
            for (String s : second) {
                if (!merged.contains(s)) {
                    merged.add(s);
                }
            }
        }
        return merged;
    }

    private String imageExtension(String url) {
        if (url == null) {
            return ".jpg";
        }
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int dot = clean.lastIndexOf('.');
        if (dot >= 0 && dot < clean.length() - 1) {
            String ext = clean.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png") || ext.equals(".webp") || ext.equals(".avif")) {
                return ext;
            }
        }
        return ".jpg";
    }

    private boolean isLoginRequiredPage(Browser browser, TbMallConfig config) {
        String url = browser.getCurrentUrl();
        if (Strings.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("login.taobao.com") || lower.contains("login_jump") || lower.contains("_____tmd_____/page/login_jump")) {
            return true;
        }
        String body = bodySnippet(browser);
        return body.contains("密码登录") && body.contains("扫码登录");
    }

    private CrawlEntryOptions createEntryOptions(TbProductDetailRequest request, TbMallConfig config) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setTaskType(DETAIL_TASK_TYPE);
        options.setProfileName(request.getProfileName());
        options.setPreflightEnabled(config.isPreflightEnabled());
        options.setPreflightUrl(config.getPreflightUrl());
        options.setPreflightStrict(config.isPreflightStrict());
        options.setPreflightCacheMinutes(config.getPreflightCacheMinutes());
        options.setForcePreflight(request.getForcePreflight() != null ? request.getForcePreflight() : config.isForcePreflight());
        options.setInitialUrl(request.getProductUrl());
        options.setLoginUrlPrefix(config.getLoginUrlPrefix());
        options.setInitialPageTimeoutSeconds(config.getInitialPageTimeoutSeconds());
        options.setLoginWaitSeconds(request.getLoginWaitSeconds() != null ? request.getLoginWaitSeconds() : config.getLoginWaitSeconds());
        options.setStepDelayMillis(config.getStepDelayMillis());
        options.setStepDelayRandomMillis(config.getStepDelayRandomMillis());
        options.setKeepBrowserOpenOnLoginRequired(request.getKeepBrowserOpenOnLoginRequired() != null
                ? request.getKeepBrowserOpenOnLoginRequired() : config.isKeepBrowserOpenOnLoginRequired());
        options.setKeepBrowserOpenSecondsOnLoginRequired(config.getKeepBrowserOpenSecondsOnLoginRequired());
        options.setLoginRequiredUrlMatcher(url -> {
            if (Strings.isEmpty(url)) return false;
            String l = url.toLowerCase(Locale.ROOT);
            return l.contains("login.taobao.com") || l.contains("login_jump") || l.contains("/login");
        });
        options.setLoginRequiredBodyMatcher(body -> {
            if (Strings.isEmpty(body)) return false;
            return (body.contains("密码登录") || body.contains("扫码登录") || body.contains("短信登录")) && body.contains("登录");
        });
        options.setLoggedInUrlMatcher(url -> {
            if (Strings.isEmpty(url)) return false;
            String l = url.toLowerCase(Locale.ROOT);
            return l.contains("item.taobao.com") || l.contains("detail.tmall.com") || l.contains("chaoshi.detail.tmall.com");
        });
        options.setLoggedInBodyMatcher(body -> {
            if (Strings.isEmpty(body)) return false;
            return body.contains("加入购物车") || body.contains("立即购买") || body.contains("商品详情") || body.contains("宝贝详情");
        });
        return options;
    }

    private TbProductDetailRequest normalizeDetailRequest(TbProductDetailRequest raw, TbMallConfig config) {
        TbProductDetailRequest req = raw != null ? raw : new TbProductDetailRequest();
        String itemId = req.getItemId();
        String url = req.getProductUrl();
        if (Strings.isEmpty(itemId) && !Strings.isEmpty(url)) {
            Matcher m = ITEM_ID_PATTERN.matcher(url);
            if (m.find()) {
                itemId = m.group(1);
            }
        }
        if (Strings.isEmpty(url) && !Strings.isEmpty(itemId)) {
            url = config.getItemUrlTemplate().replace("{itemId}", itemId);
        }
        req.setItemId(itemId != null ? itemId.trim() : "");
        req.setProductUrl(url != null ? url.trim() : "");
        if (req.getDownloadImages() == null) {
            req.setDownloadImages(false);
        }
        if (req.getIncludeSpecs() == null) {
            req.setIncludeSpecs(true);
        }
        if (Strings.isEmpty(req.getProfileName())) {
            req.setProfileName(profileManager.defaultProfileName());
        }
        req.setProfileName(profileManager.normalizeProfileName(req.getProfileName()));
        if (Strings.isEmpty(req.getOutputPath())) {
            req.setOutputPath(config.getDefaultOutputPath());
        }
        if (req.getDebugEnabled() == null) {
            req.setDebugEnabled(appConfig.getCustom().isDebugEnabled());
        }
        if (Strings.isEmpty(req.getDebugOutputDir())) {
            req.setDebugOutputDir(config.getDebugOutputDir());
        }
        return req;
    }

    private TbProductDetailResult createDetailResult(TbProductDetailRequest req) {
        TbProductDetailResult result = new TbProductDetailResult();
        result.setTaskType(DETAIL_TASK_TYPE);
        result.setItemId(req.getItemId());
        result.setProductUrl(req.getProductUrl());
        result.setProfileName(req.getProfileName());
        return result;
    }

    private WebBrowserConfig createBrowserConfig(TbMallConfig config) {
        WebBrowserConfig browserConfig = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        browserConfig.setProfileDataPath(profileManager.resolveProfileDataPath(config.getProfileName()));
        browserConfig.setHeadless(config.isHeadless());
        browserConfig.setFingerprintEnabled(config.isFingerprintEnabled());
        browserConfig.setFingerprintHeadless(config.isHeadless());
        return browserConfig;
    }

    private void humanNavigationPause(TbMallConfig config) {
        int base = config.getNavigationPauseMillis();
        int maxRandom = config.getNavigationPauseRandomMillis();
        if (base <= 0 && maxRandom <= 0) {
            return;
        }
        int delay = base + (maxRandom > 0 ? ThreadLocalRandom.current().nextInt(maxRandom) : 0);
        Extends.sleep(delay);
    }

    private void applyEntryResult(TbProductDetailResult result, CrawlEntryResult entry) {
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.setFingerprintPassed(entry.isFingerprintPassed());
        if (entry.getDiagnostics() != null) {
            result.getDiagnostics().putAll(entry.getDiagnostics());
        }
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    private void fail(TbProductDetailResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message);
    }

    private void ensureTaskDeadline(String step) throws TimeoutException {
        Long deadline = taskDeadlineHolder.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new TimeoutException("TB mall task deadline exceeded at step: " + step);
        }
    }

    private String asString(Object obj) {
        return obj == null ? "" : String.valueOf(obj).trim();
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object obj) {
        if (obj instanceof List) {
            List<?> l = (List<?>) obj;
            List<String> out = new ArrayList<String>(l.size());
            for (Object item : l) {
                if (item != null) out.add(String.valueOf(item).trim());
            }
            return out;
        }
        return Collections.emptyList();
    }

    private String bodySnippet(Browser browser) {
        String body = "";
        try {
            body = browser.executeScript("return document.body ? document.body.innerText : '';");
        } catch (Exception e) {
            return "";
        }
        return Strings.isEmpty(body) || body.length() <= 1000 ? body : body.substring(0, 1000);
    }

    private static class DebugRecorder {
        private final String baseDir;
        private final String taskDir;
        private final boolean enabled;
        private int sequence = 1;

        DebugRecorder(String itemId, String profileName, boolean debugEnabled, String explicitDir, String defaultDir) {
            this.enabled = debugEnabled;
            String root = Strings.isEmpty(explicitDir) ? defaultDir : explicitDir;
            String time = LocalDateTime.now().format(DEBUG_TIME_FORMATTER);
            String safeId = Strings.isEmpty(itemId) ? "unknown" : itemId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            this.baseDir = root;
            this.taskDir = Paths.get(root, profileName, safeId + "-" + time).toString();
        }

        boolean enabled() {
            return enabled;
        }

        Path getTaskDir() {
            return Paths.get(taskDir);
        }

        void snapshot(Browser browser, String name) {
            if (!enabled) return;
            try {
                Files.createDirectories(Paths.get(taskDir));
                String content = browser.executeScript("return document.documentElement ? document.documentElement.outerHTML : '';");
                String fileName = String.format("%02d_%s.html", sequence++, name);
                Files.write(Paths.get(taskDir, fileName), (content != null ? content : "").getBytes("UTF-8"),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.debug("Write TB mall snapshot fail: {}", e.getMessage());
            }
        }

        void snapshotText(String name, String text) {
            if (!enabled) return;
            try {
                Files.createDirectories(Paths.get(taskDir));
                String fileName = String.format("%02d_%s.txt", sequence++, name);
                Files.write(Paths.get(taskDir, fileName), (text != null ? text : "").getBytes("UTF-8"),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.debug("Write TB mall text snapshot fail: {}", e.getMessage());
            }
        }
    }
}
