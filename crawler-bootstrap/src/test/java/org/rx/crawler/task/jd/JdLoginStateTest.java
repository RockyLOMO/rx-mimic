package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JdLoginStateTest {

    @Test
    public void sessionCookieUsesNameBoundary() {
        assertFalse(JdLoginState.hasSessionCookie("wk_unb=1; pinId=2"));
        assertFalse(JdLoginState.hasSessionCookie("pt_pin=1"));
        assertTrue(JdLoginState.hasSessionCookie("pt_key=1; pt_pin=a"));
        assertTrue(JdLoginState.hasSessionCookie("thor=1; pin=abc"));
        assertTrue(JdLoginState.hasSessionCookie("thor=1; unick=abc"));
        assertFalse(JdLoginState.hasSessionCookie("thor=1; pinId=abc"));
    }

    @Test
    public void unionLoginWallBlocksStaleCookies() {
        String cookies = "thor=1; pin=abc";
        String workbench = "https://union.jd.com/proManager/index?pageNo=1";
        assertFalse(JdLoginState.unionReady(
                "https://passport.jd.com/new/login.aspx",
                "京东登录 请使用京东商城账号登录 扫码登录 密码登录",
                cookies));
        assertFalse(JdLoginState.unionReady(
                "https://union.jd.com/index?returnUrl=https%3A%2F%2Funion.jd.com%2Foverview",
                "请使用京东商城账号登录",
                cookies));
        assertFalse(JdLoginState.unionReady(workbench, "请使用京东商城账号登录\n扫码登录", cookies));
        assertFalse(JdLoginState.unionReady(workbench, "京东联盟页面正文足够长但是没有推广工作台", cookies));
    }

    @Test
    public void workbenchWithSessionCookieIsReady() {
        String cookies = "thor=1; pin=abc";
        String body = "京东联盟 我要推广 商品推广 推广管理 订单明细";
        assertTrue(JdLoginState.looksLikeUnionLoggedInBody(body));
        assertTrue(JdLoginState.unionReady("https://union.jd.com/proManager/index?pageNo=1", body, cookies));
        assertFalse(JdLoginState.unionLoginWall("https://union.jd.com/overview", body,
                "https://union.jd.com/index?returnUrl="));
    }

    @Test
    public void mallDetailDoesNotUseUnionWorkbenchText() {
        String cookies = "thor=1; pin=abc";
        String detail = "商品详情 规格参数 加入购物车 客服 店铺 评价 配送至 现货 已选 1件 优惠 发票";
        assertTrue(JdLoginState.mallReady("https://item.jd.com/100411400536.html", detail, cookies));
        assertFalse(JdLoginState.unionReady("https://item.jd.com/100411400536.html", detail, cookies));
        assertFalse(JdLoginState.mallReady(
                "https://passport.jd.com/new/login.aspx",
                "扫码登录 密码登录 短信验证码登录 忘记密码",
                cookies));
        assertFalse(JdLoginState.mallLoginWall("https://item.jd.com/100411400536.html", "密码登录"));
    }

    @Test
    public void skuIdComesFromItemLink() {
        assertEquals("100411400536", JdLoginState.skuIdFromLink("https://item.jd.com/100411400536.html"));
        assertEquals("100411400536", JdLoginState.skuIdFromLink("//item.jd.com/100411400536.html"));
        assertEquals("", JdLoginState.skuIdFromLink("https://union.jd.com/proManager/index?pageNo=1"));
    }
}
