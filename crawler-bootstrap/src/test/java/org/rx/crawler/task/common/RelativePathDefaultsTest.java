package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class RelativePathDefaultsTest {
    @Test
    public void taskStorageDefaultsShouldBeRelativeToWorkDirectory() {
        AppConfig config = new AppConfig();

        assertFalse(Paths.get(config.getBrowser().getDownloadPath()).isAbsolute());
        assertFalse(Paths.get(config.getBaseDir()).isAbsolute());
        assertFalse(Paths.get(config.getCustom().getChrome().getProfileBasePath()).isAbsolute());
        assertFalse(Paths.get(config.getCustom().getLoginKeepAlive().getUrlStorePath()).isAbsolute());
        assertFalse(Paths.get(config.getCustom().getJdUnion().getDebugOutputDir()).isAbsolute());
        assertFalse(Paths.get(config.getCustom().getTbPromotion().getDebugOutputDir()).isAbsolute());
    }
}
