package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.jd.JdMallConfig;
import org.rx.crawler.task.jd.JdMallTask;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JdSearchLoadingTest {
    @Test void thirtyInitialNodesStillScrollUntilListTailIsStable() throws Exception {
        var task = new JdMallTask(new AppConfig(), null, null, new ObjectMapper());
        Browser browser = mock(Browser.class);
        when(browser.executeScript(contains("var seen={},count=0"))).thenReturn(30,30,30,60,60,60);
        when(browser.executeScript(contains("last.bottom"))).thenReturn(false,false,true,true,true);
        var config = new JdMallConfig();
        config.setStepDelayMillis(0); config.setStepDelayRandomMillis(0);
        Method method = JdMallTask.class.getDeclaredMethod("scrollLoadMoreResults", Browser.class, JdMallConfig.class, int.class);
        method.setAccessible(true);
        method.invoke(task, browser, config, 12);
        verify(browser, times(3)).scrollPage(eq(0D), anyDouble());
    }

    @Test void failedNativeInputNeverUsesJavascriptValueOrSyntheticEvents() throws Exception {
        var task = new JdMallTask(new AppConfig(), null, null, new ObjectMapper());
        Browser browser = mock(Browser.class);
        when(browser.executeScript(anyString())).thenReturn(true);
        when(browser.executeScript(anyString(), eq("ddr3内存自营"))).thenReturn(false);
        doThrow(new IllegalStateException("native blocked")).when(browser).elementClick(anyString(), eq(false));
        Method method = JdMallTask.class.getDeclaredMethod("nativeSetSearchValue", Browser.class, String.class);
        method.setAccessible(true);
        assertEquals(false, method.invoke(task, browser, "ddr3内存自营"));
        verify(browser, never()).executeScript(matches("(?s).*dispatchEvent.*"), any(Object[].class));
        verify(browser, never()).executeScript(matches("(?s).*(setter\\.set|chosen\\.value=).*"), any(Object[].class));
    }
}
