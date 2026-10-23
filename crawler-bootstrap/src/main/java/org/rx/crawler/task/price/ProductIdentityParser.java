package org.rx.crawler.task.price;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 只从确认过的商品页提取数字 ID。联盟中转、登录页和任意 id 参数都不算。 */
public final class ProductIdentityParser {
    public enum PageKind {
        PRODUCT, LOGIN, INTERMEDIATE, OTHER
    }

    public static final class Hit {
        public final PageKind kind;
        public final String productId;

        Hit(PageKind kind, String productId) {
            this.kind = kind;
            this.productId = productId;
        }
    }

    private static final Set<String> JD_ITEM_HOSTS = Set.of("item.jd.com", "item.m.jd.com");
    private static final Set<String> TB_ITEM_HOSTS = Set.of("item.taobao.com", "item.tmall.com", "detail.tmall.com");
    private static final Set<String> LOGIN_HOSTS = Set.of(
            "passport.jd.com", "login.jd.com", "login.taobao.com", "login.tmall.com", "passport.taobao.com");
    private static final Set<String> INTERMEDIATE_HOSTS = Set.of(
            "union.jd.com", "union-click.jd.com", "u.jd.com", "pub.alimama.com",
            "s.click.taobao.com", "click.taobao.com", "uland.taobao.com", "tb.cn");

    private ProductIdentityParser() {
    }

    public static Hit inspect(int platform, String rawUrl) {
        URI uri = parse(rawUrl);
        if (uri == null) {
            return new Hit(PageKind.OTHER, "");
        }
        String host = host(uri);
        if (LOGIN_HOSTS.contains(host) || host.contains("havana")) {
            return new Hit(PageKind.LOGIN, "");
        }
        if (INTERMEDIATE_HOSTS.contains(host)) {
            return new Hit(PageKind.INTERMEDIATE, "");
        }
        if (platform == PriceCodes.JD && JD_ITEM_HOSTS.contains(host)) {
            String id = jdPathId(uri.getPath());
            if (id.isEmpty() && "item.m.jd.com".equals(host) && "/ware/view.action".equals(uri.getPath())) {
                id = queryValue(uri.getRawQuery(), "wareId");
            }
            return id.isEmpty() ? new Hit(PageKind.OTHER, "") : new Hit(PageKind.PRODUCT, id);
        }
        if (platform == PriceCodes.TB && TB_ITEM_HOSTS.contains(host) && itemPath(uri.getPath())) {
            String id = queryId(uri.getRawQuery());
            return id.isEmpty() ? new Hit(PageKind.OTHER, "") : new Hit(PageKind.PRODUCT, id);
        }
        return new Hit(PageKind.OTHER, "");
    }

    private static URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("//")) {
            trimmed = "https:" + trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String host(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            return host.substring(4);
        }
        return host;
    }

    private static String jdPathId(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (!name.endsWith(".html")) {
            return "";
        }
        String id = name.substring(0, name.length() - 5);
        return digits(id) ? id : "";
    }

    private static boolean itemPath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith("/item.htm") || lower.endsWith("/item.html");
    }

    private static String queryId(String query) {
        return queryValue(query, "id");
    }

    private static String queryValue(String query, String key) {
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (!key.equals(part.substring(0, eq))) {
                continue;
            }
            String value = part.substring(eq + 1);
            return digits(value) ? value : "";
        }
        return "";
    }

    public static boolean isNumeric(String value) {
        return digits(value);
    }

    public static String canonicalUrl(int platform, String productId) {
        if (!digits(productId) || (platform != PriceCodes.JD && platform != PriceCodes.TB)) {
            throw new IllegalArgumentException("Numeric product identity required");
        }
        return platform == PriceCodes.JD ? "https://item.jd.com/" + productId + ".html"
                : "https://item.taobao.com/item.htm?id=" + productId;
    }

    private static boolean digits(String value) {
        if (value == null || value.isEmpty() || value.length() > 20) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
