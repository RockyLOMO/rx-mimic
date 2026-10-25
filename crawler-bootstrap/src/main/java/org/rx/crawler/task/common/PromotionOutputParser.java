package org.rx.crawler.task.common;

import org.rx.crawler.task.price.PriceCodes;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Extract share output only; never creates links or initiates network requests. */
public final class PromotionOutputParser {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'【】「」『』“”¥￥]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TB_TOKEN = Pattern.compile("(?=([¥￥])([A-Za-z0-9_\\s-]{5,160})([¥￥]))");
    private PromotionOutputParser() { }

    public static String firstShortUrl(String text, int platform) {
        var matches = URL.matcher(text == null ? "" : text);
        while (matches.find()) {
            String candidate = matches.group().replaceFirst("[，。；,;）)]+$", "");
            try {
                URI uri = URI.create(candidate);
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1) continue;
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                boolean match = platform == PriceCodes.JD
                        ? host.equals("u.jd.com") || host.equals("3.cn") || host.endsWith(".3.cn")
                        : platform == PriceCodes.TB && (host.equals("tb.cn") || host.endsWith(".tb.cn")
                            || host.equals("s.click.taobao.com") && uri.getRawQuery() == null && uri.getRawFragment() == null
                                && uri.getPath().matches("/[A-Za-z0-9_-]+"));
                if (match) return candidate;
            } catch (IllegalArgumentException ignored) { }
        }
        return "";
    }

    public static String taobaoToken(String text) {
        var token = TB_TOKEN.matcher(text == null ? "" : text);
        while (token.find()) {
            String body = token.group(2).trim().replaceAll("\\s+", " ");
            if (body.matches(".*[A-Za-z].*")) return token.group(1) + body + token.group(3);
        }
        return "";
    }

    /** Caller supplies the short-command block, not a full JD marketing document. */
    public static String jdShortToken(String text) {
        if (text == null) return "";
        String value = text.trim().replaceFirst("^短口令[：:\\s]*", "");
        int first = -1;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '!' || value.charAt(i) == '！') { first = i; break; }
        if (first < 0) return "";
        int second = -1;
        for (int i = first + 1; i < value.length(); i++) if (value.charAt(i) == '!' || value.charAt(i) == '！') { second = i; break; }
        if (second <= first + 1) return "";
        // No title prefix or copy-button/help text belongs to a short command.
        if (!value.substring(0, first).isBlank()) return "";
        return value.replaceAll("\\s+", " ").trim();
    }
}
