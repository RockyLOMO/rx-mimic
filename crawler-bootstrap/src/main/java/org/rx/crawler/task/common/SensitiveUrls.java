package org.rx.crawler.task.common;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 日志和通知里的地址只留协议、主机和路径。 */
public final class SensitiveUrls {
    private static final Pattern EMBEDDED = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    private SensitiveUrls() {
    }

    public static String redact(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.getScheme() == null) {
                return stripRaw(trimmed);
            }
            String host = uri.getHost();
            if (host == null) {
                return stripRaw(trimmed);
            }
            return new URI(uri.getScheme(), null, host, uri.getPort(), uri.getRawPath(), null, null).toString();
        } catch (Exception e) {
            return stripRaw(trimmed);
        }
    }

    public static String redactText(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        Matcher matcher = EMBEDDED.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(redact(matcher.group())));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String stripRaw(String url) {
        String withoutUser = url.replaceFirst("(?i)^(https?://)[^/@\\s]+@", "$1");
        int query = withoutUser.indexOf('?');
        int fragment = withoutUser.indexOf('#');
        int cut = -1;
        if (query >= 0) {
            cut = query;
        }
        if (fragment >= 0 && (cut < 0 || fragment < cut)) {
            cut = fragment;
        }
        return cut >= 0 ? withoutUser.substring(0, cut) : withoutUser;
    }
}
