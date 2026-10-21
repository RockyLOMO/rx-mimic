package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SensitiveUrlsTest {
    @Test
    void stripsQueryFragmentAndUserInfo() {
        assertEquals("https://example.com/captcha",
                SensitiveUrls.redact("https://user:secret@example.com/captcha?token=abc#frag"));
        assertEquals("https://pub.alimama.com/punish",
                SensitiveUrls.redact("https://pub.alimama.com/punish?x5secdata=secret-token"));
        assertEquals("https://item.taobao.com/item.htm",
                SensitiveUrls.redact("https://item.taobao.com/item.htm#detail"));
        assertEquals("", SensitiveUrls.redact("  "));
        assertEquals("https://example.com/captcha",
                SensitiveUrls.redact("https://example.com/captcha?token=abc"));
    }

    @Test
    void redactsUrlsInsideMessages() {
        String text = SensitiveUrls.redactText("open https://example.com/captcha?token=abc failed");
        assertFalse(text.contains("token"));
        assertEquals("open https://example.com/captcha failed", text);
    }
}
