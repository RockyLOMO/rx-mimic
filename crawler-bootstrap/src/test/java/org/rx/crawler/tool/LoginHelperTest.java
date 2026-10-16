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
