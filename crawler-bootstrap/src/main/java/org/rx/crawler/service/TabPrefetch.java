package org.rx.crawler.service;

import java.net.URI;
import java.util.Locale;

/**
 * 队列在 chrome-owner 上登记「当前任务起始页」和「下一个排队任务起始页」。
 * 浏览器导航时认领当前页；后台标签只负责把下一页打开到首包，不操作它。
 * 只有创建页面的那条线程会读写，不另开 Chrome。
 */
public final class TabPrefetch {
    private static volatile Plan plan;

    private TabPrefetch() {
    }

    public static void arm(String adoptUrl, String nextUrl) {
        plan = new Plan(normalize(adoptUrl), normalize(nextUrl));
    }

    public static void clear() {
        plan = null;
    }

    /** 这次导航是当前任务的起始页时认领一次。之后同地址的导航照常重新打开。 */
    public static boolean claimAdopt(String url) {
        Plan current = plan;
        if (current == null || current.adoptClaimed || current.adoptUrl.isEmpty()) {
            return false;
        }
        if (!same(current.adoptUrl, url)) {
            return false;
        }
        current.adoptClaimed = true;
        return true;
    }

    public static String peekNext() {
        Plan current = plan;
        if (current == null || current.nextStarted || current.nextUrl.isEmpty()) {
            return null;
        }
        return current.nextUrl;
    }

    /** 这个地址已经在后台标签上，预加载下一个任务时不能把该标签覆盖掉。 */
    public static String adoptUrl() {
        Plan current = plan;
        if (current == null) {
            return "";
        }
        return current.adoptUrl;
    }

    public static void markNextStarted() {
        Plan current = plan;
        if (current != null) {
            current.nextStarted = true;
        }
    }

    public static boolean same(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    public static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme.isEmpty() || host.isEmpty()) {
                return trimmed;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            if ("/".equals(path)) {
                path = "";
            }
            StringBuilder out = new StringBuilder();
            out.append(scheme).append("://").append(host);
            if (uri.getPort() > 0) {
                out.append(':').append(uri.getPort());
            }
            out.append(path);
            if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
                out.append('?').append(uri.getQuery());
            }
            return out.toString();
        } catch (Exception e) {
            return trimmed;
        }
    }

    /** 日志只留协议、主机和路径，不带 query。 */
    public static String brief(String url) {
        String normalized = normalize(url);
        if (normalized.isEmpty()) {
            return "-";
        }
        int query = normalized.indexOf('?');
        if (query >= 0) {
            return normalized.substring(0, query);
        }
        return normalized;
    }

    private static final class Plan {
        private final String adoptUrl;
        private final String nextUrl;
        private boolean adoptClaimed;
        private boolean nextStarted;

        private Plan(String adoptUrl, String nextUrl) {
            this.adoptUrl = adoptUrl == null ? "" : adoptUrl;
            this.nextUrl = nextUrl == null ? "" : nextUrl;
        }
    }
}
