package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JdLoginKeysTest {
    @Test
    public void loadReadsRootAndNestedAccount() throws Exception {
        Path root = Files.createTempFile("jd-login-keys", ".json");
        Path nested = Files.createTempFile("jd-login-keys-nested", ".json");
        try {
            Files.writeString(root, "{\"usr\":\"13800000000\",\"pwd\":\"secret-1\"}");
            Files.writeString(nested, "{\"jd_union\":{\"usr\":\"13900000000\",\"pwd\":\"secret-2\"}}");
            JdLoginKeys.Credentials fromRoot = JdLoginKeys.load(root);
            JdLoginKeys.Credentials fromNested = JdLoginKeys.load(nested);
            assertEquals("13800000000", fromRoot.username());
            assertEquals("secret-1", fromRoot.password());
            assertEquals("13900000000", fromNested.username());
            assertEquals("secret-2", fromNested.password());
        } finally {
            Files.deleteIfExists(root);
            Files.deleteIfExists(nested);
        }
    }

    @Test
    public void missingOrEmptyFileReturnsNull() throws Exception {
        assertNull(JdLoginKeys.load(""));
        assertNull(JdLoginKeys.load(Path.of("missing-jd-login-keys.json")));
        Path empty = Files.createTempFile("jd-login-keys-empty", ".json");
        try {
            Files.writeString(empty, "{\"usr\":\"13800000000\",\"pwd\":\"\"}");
            assertNull(JdLoginKeys.load(empty));
        } finally {
            Files.deleteIfExists(empty);
        }
    }
}
