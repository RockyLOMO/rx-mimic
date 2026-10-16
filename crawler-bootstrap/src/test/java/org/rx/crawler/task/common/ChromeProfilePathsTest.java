package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChromeProfilePathsTest {

    @Test
    public void blankBaseUsesOneUserProfile() {
        Path base = ChromeProfilePaths.userBaseDir();
        assertEquals(Paths.get("rx-mimic", "chrome"), base.getParent().getFileName().resolve(base.getFileName()));
        assertTrue(base.endsWith(Paths.get("rx-mimic", "chrome")));
        assertEquals(base.resolve("common"), ChromeProfilePaths.profileDir(null, null));
        assertEquals(base.resolve("common"), ChromeProfilePaths.profileDir("  ", ""));
        assertEquals(base.resolve("jd-union-5"), ChromeProfilePaths.profileDir(null, "jd union/5"));
    }

    @Test
    public void explicitBaseKeepsItsOwnProfile() {
        Path base = Paths.get("D:/custom/chrome").toAbsolutePath().normalize();
        assertEquals(base.resolve("shop"), ChromeProfilePaths.profileDir(base.toString(), "shop"));
    }

    @Test
    public void importCopiesLegacyCookiesOnce(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("common_pool_0");
        Path target = temp.resolve("moved");
        Files.createDirectories(source.resolve("Default/Network"));
        Files.write(source.resolve("Local State"), "os-crypt-key".getBytes(StandardCharsets.US_ASCII));
        Files.write(source.resolve("Default/Network/Cookies"), "thor-tracknick".getBytes(StandardCharsets.US_ASCII));
        Files.createDirectories(target.resolve("Default/Network"));
        Files.write(target.resolve("Default/Network/Cookies-wal"), "stale".getBytes(StandardCharsets.US_ASCII));

        assertTrue(ChromeProfilePaths.importFirstAvailable(target, source));
        assertFalse(ChromeProfilePaths.importFirstAvailable(target, source));
        assertEquals("os-crypt-key", Files.readString(target.resolve("Local State")));
        assertEquals("thor-tracknick", Files.readString(target.resolve("Default/Network/Cookies")));
        assertFalse(Files.exists(target.resolve("Default/Network/Cookies-wal")));
        assertFalse(ChromeProfilePaths.importLegacyDefaultProfile(target.resolve("common")));
    }
}
