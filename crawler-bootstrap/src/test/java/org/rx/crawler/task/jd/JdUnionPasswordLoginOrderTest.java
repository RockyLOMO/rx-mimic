package org.rx.crawler.task.jd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.rx.crawler.service.Browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录提交顺序：京东 bind-suspend 会上报页面加载起的全部鼠标移动。
 * 必须在点「登录」之前先有一次连续的光标移动和停顿，不能填完就跳到按钮上。
 * 用假密钥文件，不读真实账号。
 */
class JdUnionPasswordLoginOrderTest {

    private static final String PASSWORD = "input[type='password']";

    @Test
    void pointerDriftHappensBeforeTheLoginClick(@TempDir Path dir) throws Exception {
        Path keys = dir.resolve("dummy-keys.json");
        Files.writeString(keys, "{\"usr\":\"dummy-user\",\"pwd\":\"dummy-pass\"}");

        Browser browser = mock(Browser.class);
        when(browser.getCurrentUrl()).thenReturn("https://union.jd.com/index?returnUrl=x");
        when(browser.locateInFrames(anyString())).thenAnswer(call ->
                PASSWORD.equals(call.getArgument(0)) ? box(300, 260) : null);
        when(browser.locateTextInFrames(anyString(), anyBoolean(), anyDouble(), anyDouble())).thenReturn(null);
        when(browser.executeScript(anyString())).thenReturn(null);
        when(browser.typeAboveInFrames(anyString(), anyString())).thenReturn(true);
        when(browser.typeInFrames(anyString(), anyString())).thenReturn(true);
        when(browser.clickTextInFrames(anyString(), anyString(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble())).thenReturn(true);

        JdUnionConfig config = new JdUnionConfig();
        config.setLoginKeysFile(keys.toString());

        boolean cleared = new JdUnionPasswordLogin()
                .attempt(browser, config, null, candidate -> false);

        assertTrue(cleared, "login wall should be reported cleared");
        InOrder order = inOrder(browser);
        order.verify(browser).mouseMove(anyDouble(), anyDouble());
        order.verify(browser).clickTextInFrames(anyString(), anyString(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble());
    }

    private static Map<String, Object> box(double centerX, double centerY) {
        Map<String, Object> box = new LinkedHashMap<String, Object>();
        box.put("x", centerX);
        box.put("y", centerY);
        box.put("width", 280);
        box.put("height", 36);
        return box;
    }
}
