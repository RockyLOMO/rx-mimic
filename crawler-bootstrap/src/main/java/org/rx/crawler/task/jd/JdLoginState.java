package org.rx.crawler.task.jd;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 京东登录判断只有两套，不能混用。
 * 联盟：搜索可转链商品、转链、订单，以及登录脚本打开联盟推广页后的确认。
 * 主站：商品详情（item.jd.com）。主站搜索和详情是同一个京东账号会话，也走这一套。
 */
public final class JdLoginState {
    private static final Pattern SKU_IN_LINK = Pattern.compile("item\\.jd\\.com/(\\d+)");

    private JdLoginState() {
    }

    public static boolean hasSessionCookie(String rawCookie) {
        boolean passport = hasCookieName(rawCookie, "pt_key") && hasCookieName(rawCookie, "pt_pin");
        boolean thor = hasCookieName(rawCookie, "thor")
                && (hasCookieName(rawCookie, "pin") || hasCookieName(rawCookie, "unick"));
        return passport || thor;
    }

    /**
     * 联盟页已登录：不在联盟登录墙，正文是推广工作台，并且有会话 Cookie。
     */
    public static boolean unionReady(String url, String body, String rawCookie) {
        return unionReady(url, body, rawCookie, "https://union.jd.com/index?returnUrl=");
    }

    public static boolean unionReady(String url, String body, String rawCookie, String unionLoginPrefix) {
        if (unionLoginWall(url, body, unionLoginPrefix)) {
            return false;
        }
        if (!looksLikeUnionLoggedInBody(body)) {
            return false;
        }
        return hasSessionCookie(rawCookie);
    }

    public static boolean unionLoginWall(String url, String body, String unionLoginPrefix) {
        return unionLoginWallUrl(url, unionLoginPrefix) || looksLikeUnionLoginBody(body);
    }

    /**
     * 主站商品页已登录：不在主站登录墙，正文已经出来，并且有会话 Cookie。
     * 不看「我要推广」。详情页上单独出现「密码登录」也不算登录墙。
     */
    public static boolean mallReady(String url, String body, String rawCookie) {
        if (mallLoginWall(url, body)) {
            return false;
        }
        if (body == null || body.trim().length() < 20) {
            return false;
        }
        return hasSessionCookie(rawCookie);
    }

    public static boolean mallLoginWall(String url, String body) {
        if (url != null && !url.isEmpty()) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.contains("passport.jd.com") || lower.contains("/login")) {
                return true;
            }
        }
        return looksLikeMallLoginBody(body);
    }

    public static boolean unionLoginWallUrl(String currentUrl, String loginUrlPrefix) {
        if (currentUrl == null || currentUrl.isEmpty()) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        boolean prefix = loginUrlPrefix != null && !loginUrlPrefix.isEmpty() && currentUrl.startsWith(loginUrlPrefix);
        return prefix
                || lower.contains("passport.jd.com")
                || lower.contains("/login")
                || lower.contains("union.jd.com/index?");
    }

    /**
     * 联盟页正文像登录框。已出现推广工作台文案时不算登录墙。
     */
    public static boolean looksLikeUnionLoginBody(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        boolean loginSignal = containsAny(body,
                "请使用京东商城账号登录",
                "密码登录",
                "短信登录",
                "手机扫码安全登录",
                "账号登录扫码登录",
                "个人用户登录",
                "企业用户登录",
                "请使用京东APP");
        boolean workSignal = containsAny(body, "我要推广", "商品推广", "推客推广", "推广管理");
        return loginSignal && !workSignal;
    }

    public static boolean looksLikeUnionLoggedInBody(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        return containsAny(body, "我要推广", "商品推广", "推广管理", "推客推广")
                && !looksLikeUnionLoginBody(body);
    }

    /**
     * 主站登录页要同时命中两个强特征。首页只有「请登录」入口不算。
     */
    public static boolean looksLikeMallLoginBody(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        int hits = 0;
        if (body.contains("扫码登录")) {
            hits++;
        }
        if (body.contains("密码登录")) {
            hits++;
        }
        if (body.contains("短信验证码登录")) {
            hits++;
        }
        if (body.contains("忘记密码")) {
            hits++;
        }
        return hits >= 2;
    }

    public static String skuIdFromLink(String link) {
        if (link == null || link.isEmpty()) {
            return "";
        }
        Matcher matcher = SKU_IN_LINK.matcher(link);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static boolean hasCookieName(String rawCookie, String name) {
        if (rawCookie == null || name == null || name.isEmpty()) {
            return false;
        }
        String token = name + "=";
        int from = 0;
        while (from <= rawCookie.length() - token.length()) {
            int index = rawCookie.indexOf(token, from);
            if (index < 0) {
                return false;
            }
            if (index == 0) {
                return true;
            }
            char previous = rawCookie.charAt(index - 1);
            if (previous == ' ' || previous == ';') {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean containsAny(String value, String... keys) {
        for (String key : keys) {
            if (value.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
