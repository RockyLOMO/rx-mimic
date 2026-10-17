package org.rx.crawler.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.task.common.ChromeProfilePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoginHelperTest {

    @Test
    public void taobaoLoginWallIsNotReadyEvenWithOldCookies() {
        String cookies = "tracknick=abc; cookie2=1";
        String ali = "cookie2_alimama=1; t_alimama=1";
        String jump = "https://pub.alimama.com/portal/v2/home/plus/index.htm/_____tmd_____/page/login_jump";
        assertFalse(LoginHelper.tbSessionReady(jump, "快速进入 确认登录 淘宝联盟首页内容足够长", cookies, ali));
        assertFalse(LoginHelper.tbSessionReady(
                "https://pub.alimama.com/portal/v2/home/plus/index.htm",
                "请使用手机淘宝扫码登录，或密码登录后进入联盟", cookies, ali));
        assertFalse(LoginHelper.tbSessionReady(
                "https://pub.alimama.com/portal/v2/home/plus/index.htm",
                "快速进入", cookies, ali));
        assertFalse(LoginHelper.tbSessionReady(
                "https://pub.alimama.com/portal/v2/home/plus/index.htm", "", cookies, ali));
        assertTrue(LoginHelper.tbSessionReady(
                "https://pub.alimama.com/portal/v2/home/plus/index.htm",
                "淘宝联盟 选品广场 我的推广 订单明细 退出登录", cookies, ali));
    }

    @Test
    public void validJdSessionSkipsLoginPage() {
        assertEquals("https://union.jd.com/proManager/index?pageNo=1",
                LoginHelper.jdEntryUrl("thor=1; pin=abc"));
        assertEquals("https://union.jd.com/proManager/index?pageNo=1",
                LoginHelper.jdEntryUrl("pt_key=1; pt_pin=abc"));
        assertEquals("https://passport.jd.com/new/login.aspx", LoginHelper.jdEntryUrl(""));
        assertEquals("https://passport.jd.com/new/login.aspx", LoginHelper.jdEntryUrl("pt_key=1"));
    }

    @Test
    public void cookieNameDoesNotMatchPrefix() {
        assertFalse(LoginHelper.hasCookieName("wk_unb=1; pinId=2", "unb"));
        assertFalse(LoginHelper.hasCookieName("pt_pin=1; pinId=2", "pin"));
        assertTrue(LoginHelper.hasCookieName("pin=1; thor=2", "pin"));
        assertTrue(LoginHelper.hasCookieName("thor=2; pin=1", "thor"));
        assertTrue(LoginHelper.hasCookieName("a=1; tracknick=abc", "tracknick"));
        assertTrue(LoginHelper.hasCookieName("cookie2_alimama=1", "cookie2_alimama"));
        assertFalse(LoginHelper.hasCookieName(null, "thor"));
    }

    @Test
    public void copyCookiePairReplacesStaleWal(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("pool");
        Path target = temp.resolve("common");
        Files.createDirectories(source.resolve("Default/Network"));
        Files.createDirectories(target.resolve("Default/Network"));
        byte[] cookies = "host-thor-tracknick".getBytes(StandardCharsets.US_ASCII);
        Files.write(source.resolve("Local State"), "os-crypt-key".getBytes(StandardCharsets.US_ASCII));
        Files.write(source.resolve("Default/Network/Cookies"), cookies);
        Files.write(target.resolve("Default/Network/Cookies-wal"), "stale-wal".getBytes(StandardCharsets.US_ASCII));
        Files.write(target.resolve("Local State"), "old-key".getBytes(StandardCharsets.US_ASCII));

        ChromeProfilePaths.copyCookiePair(source, target);

        assertEquals("os-crypt-key", Files.readString(target.resolve("Local State")));
        assertEquals("host-thor-tracknick", Files.readString(target.resolve("Default/Network/Cookies")));
        assertFalse(Files.exists(target.resolve("Default/Network/Cookies-wal")));
        assertTrue(LoginHelper.containsAscii(Files.readAllBytes(target.resolve("Default/Network/Cookies")), "thor"));
        assertTrue(LoginHelper.containsAscii(Files.readAllBytes(target.resolve("Default/Network/Cookies")), "tracknick"));
    }
}
