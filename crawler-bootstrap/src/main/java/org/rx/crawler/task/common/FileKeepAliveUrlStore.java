package org.rx.crawler.task.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileKeepAliveUrlStore implements KeepAliveUrlStore {
    private static final TypeReference<LinkedHashMap<String, List<String>>> STORE_TYPE =
            new TypeReference<LinkedHashMap<String, List<String>>>() {
            };

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public List<String> getCandidateUrls(String platform, List<String> defaults) {
        List<String> candidates = mergeUrls(loadUrls(platform), defaults,
                Math.max(1, appConfig.getCustom().getLoginKeepAlive().getMaxUrlsPerPlatform()));
        return candidates.isEmpty() ? defaults : candidates;
    }

    @Override
    public void collect(String platform, Browser browser, Map<String, Object> diagnostics) {
        if (SensitiveCrawlScope.active()) return;
        AppConfig.LoginKeepAliveConfig config = appConfig.getCustom().getLoginKeepAlive();
        if (browser == null || config == null || !config.isHarvestEnabled()) {
            return;
        }
        try {
            Map<String, Object> links = browser.executeScript("var hs=[];" +
                    "Array.prototype.slice.call(document.querySelectorAll('a[href]')).forEach(function(a){" +
                    "var h=a.getAttribute('href')||a.href||'';" +
                    "if(!h){return;}" +
                    "try{hs.push(new URL(h, location.href).href);}catch(e){}" +
                    "});return {currentUrl:location.href,hrefs:hs};");
            String currentUrl = stringValue(links.get("currentUrl"));
            List<String> hrefs = toStringList(links.get("hrefs"));
            List<String> filtered = filterSameDomainUrls(currentUrl, hrefs);
            List<String> picked = pickRandomUrls(filtered, Math.max(1, config.getHarvestPerRunCount()));
            if (diagnostics != null) {
                diagnostics.put("keepAliveCurrentUrl", currentUrl);
                diagnostics.put("keepAliveHarvestCandidateCount", filtered.size());
                diagnostics.put("keepAliveHarvestSavedCount", picked.size());
                diagnostics.put("keepAliveHarvestUrls", picked);
            }
            if (picked.isEmpty()) {
                return;
            }
            saveUrls(platform, mergeUrls(picked, loadUrls(platform), Math.max(1, config.getMaxUrlsPerPlatform())));
        } catch (Exception e) {
            log.warn("Collect keep-alive urls fail, platform={}, error={}", platform, e.getMessage(), e);
            if (diagnostics != null) {
                diagnostics.put("keepAliveHarvestError", e.getMessage());
            }
        }
    }

    List<String> filterSameDomainUrls(String currentUrl, List<String> hrefs) {
        if (Strings.isEmpty(currentUrl) || hrefs == null || hrefs.isEmpty()) {
            return Collections.emptyList();
        }
        URI current = safeUri(currentUrl);
        if (current == null || Strings.isEmpty(current.getHost())) {
            return Collections.emptyList();
        }
        String host = current.getHost().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        for (String href : hrefs) {
            URI uri = safeUri(href);
            if (uri == null || Strings.isEmpty(uri.getScheme()) || Strings.isEmpty(uri.getHost())) {
                continue;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String hrefHost = uri.getHost().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                continue;
            }
            if (!host.equals(hrefHost)) {
                continue;
            }
            String normalized = normalizeUrl(uri);
            if (Strings.isEmpty(normalized) || isIgnoredUrl(normalized)) {
                continue;
            }
            values.add(normalized);
        }
        return new ArrayList<String>(values);
    }

    List<String> mergeUrls(List<String> preferred, List<String> secondary, int maxSize) {
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        if (preferred != null) {
            merged.addAll(preferred);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        List<String> urls = new ArrayList<String>(merged);
        return urls.size() <= maxSize ? urls : new ArrayList<String>(urls.subList(0, maxSize));
    }

    private List<String> loadUrls(String platform) {
        lock.lock();
        try {
            Map<String, List<String>> store = readStore();
            List<String> urls = store.get(platform);
            return urls == null ? new ArrayList<String>() : new ArrayList<String>(urls);
        } finally {
            lock.unlock();
        }
    }

    void saveUrls(String platform, List<String> urls) {
        lock.lock();
        try {
            Map<String, List<String>> store = readStore();
            store.put(platform, new ArrayList<String>(urls));
            Path path = storePath();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(store),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception e) {
            throw new IllegalStateException("Save keep-alive url store fail", e);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, List<String>> readStore() {
        Path path = storePath();
        if (!Files.exists(path)) {
            return new LinkedHashMap<String, List<String>>();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return new LinkedHashMap<String, List<String>>();
            }
            Map<String, List<String>> store = objectMapper.readValue(bytes, STORE_TYPE);
            return store == null ? new LinkedHashMap<String, List<String>>() : store;
        } catch (Exception e) {
            log.warn("Read keep-alive url store fail, path={}, error={}", path, e.getMessage(), e);
            return new LinkedHashMap<String, List<String>>();
        }
    }

    private Path storePath() {
        String path = appConfig.getCustom().getLoginKeepAlive().getUrlStorePath();
        if (Strings.isEmpty(path)) {
            path = "./data/keepalive/urls.json";
        }
        return Paths.get(path);
    }

    private List<String> pickRandomUrls(List<String> urls, int limit) {
        if (urls == null || urls.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> shuffled = new ArrayList<String>(urls);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = ThreadLocalRandom.current().nextInt(i + 1);
            String tmp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, tmp);
        }
        return shuffled.size() <= limit ? shuffled : new ArrayList<String>(shuffled.subList(0, limit));
    }

    private URI safeUri(String url) {
        try {
            return URI.create(url);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeUrl(URI uri) {
        try {
            URI normalized = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
            return normalized.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isIgnoredUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("logout")
                || lower.contains("login")
                || lower.contains("signout")
                || lower.contains("javascript:")
                || lower.contains("mailto:")
                || lower.contains("tel:")
                || lower.contains("/help")
                || lower.contains("/customer");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<Object> raw = (List<Object>) value;
        List<String> list = new ArrayList<String>(raw.size());
        for (Object item : raw) {
            if (item != null) {
                list.add(String.valueOf(item));
            }
        }
        return list;
    }
}
