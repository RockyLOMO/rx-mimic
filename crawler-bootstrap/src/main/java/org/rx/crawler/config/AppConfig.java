package org.rx.crawler.config;

import com.alibaba.fastjson2.TypeReference;
import lombok.Data;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.dto.CrawlPageConfig;
import org.rx.crawler.task.common.ChromeProfileConfig;
import org.rx.crawler.task.jd.JdMallConfig;
import org.rx.crawler.task.jd.JdUnionConfig;
import org.rx.crawler.task.tb.TbPromotionConfig;
import org.rx.util.IdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

import static org.rx.core.Sys.fromJson;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
@RefreshScope
@Validated
@Order(1)
public class AppConfig {
    public static final String CACHE_PDD_GOODS_MAP = "RubbishPdd";

    @Data
    public static class BrowserPoolConfig {
        private long waitMillis = 500;
        private int pageLoadTimeoutSeconds = 30;
        private int findElementTimeoutSeconds = 6;
        private boolean headless = false;
        private String profileDataPath;
        private String downloadPath = "./temp/";
        private BrowserWindowRect windowRectangle = new BrowserWindowRect(0, 0, -1, -1);
        private String configureScriptExecutorType = "org.rx.crawler.service.impl.ApiConfigureScriptExecutor";
        private boolean fingerprintEnabled = false;
        private String fingerprintScriptPath = "/bot/chrome-fingerprint.js";
        private String fingerprintStealthScriptPath = "/static/js/stealth.min.js";
        private String fingerprintCheckUrl = "https://bot.sannysoft.com/";
        private boolean fingerprintHeadless = false;
        private boolean fingerprintDiagnostics = false;
        private String playwrightChannel = "chrome";
        private String playwrightExecutablePath;
        /**
         * 空=自动跟随运行机器默认语言；仅在需要强制指定时配置（如 zh-CN）。
         */
        private String locale;
        /**
         * 空=自动跟随运行机器默认时区；仅在需要强制指定时配置。
         */
        private String timezoneId;
        private String userAgent;
        private boolean blockResourceEnabled = false;
        private boolean humanInputEnabled = true;
        private int humanActionMinDelayMillis = 180;
        private int humanActionMaxDelayMillis = 650;
        private int operationRandomMinDelayMillis = 80;
        private int operationRandomMaxDelayMillis = 320;
        private int mouseMoveMinSteps = 24;
        private int mouseMoveMaxSteps = 56;
        private int typingMinDelayMillis = 90;
        private int typingMaxDelayMillis = 260;
    }

    @Data
    public static class CustomTaskConfig {
        private boolean remotingEnabled = true;
        private int remotingListenPort;
        private int queueMaxConcurrency = 1;
        private int queueTimeoutSeconds = 600;
        private boolean debugEnabled = false;
        private int maxTaskMinutes = 4;
        private LoginNotificationConfig loginNotification = new LoginNotificationConfig();
        private LoginKeepAliveConfig loginKeepAlive = new LoginKeepAliveConfig();
        private ChromeProfileConfig chrome = new ChromeProfileConfig();
        private JdUnionConfig jdUnion = new JdUnionConfig();
        private JdMallConfig jdMall = new JdMallConfig();
        private TbPromotionConfig tbPromotion = new TbPromotionConfig();
        private org.rx.crawler.task.tb.TbMallConfig tbMall = new org.rx.crawler.task.tb.TbMallConfig();
    }

    @Data
    public static class LoginNotificationConfig {
        private boolean enabled = false;
        private long minIntervalSeconds = 300;
        private MailNotificationConfig mail = new MailNotificationConfig();
    }

    @Data
    public static class MailNotificationConfig {
        private boolean enabled = true;
        private String subjectPrefix = "[rx-mimic]";
        private String keysFile = "D:\\home\\.envKeys\\keys.json";
        private String to = "ilovehaley.kid@gmail.com";
    }

    @Data
    public static class LoginKeepAliveConfig {
        private boolean enabled = false;
        private boolean jdEnabled = true;
        private boolean tbEnabled = true;
        private long initialDelayMillis = 60000;
        private long fixedDelayMillis = 1800000;
        private int pageTimeoutSeconds = 60;
        private boolean harvestEnabled = true;
        private int harvestPerRunCount = 5;
        private int maxUrlsPerPlatform = 50;
        private String urlStorePath = "./data/keepalive/urls.json";
        private String startTime;
        private String endTime;
    }

    private BrowserPoolConfig browser = new BrowserPoolConfig();
    private CustomTaskConfig custom = new CustomTaskConfig();

    private int fiddlerListenPort;
    private String cleanTaskTime = "03:00:00";
    private String baseDir = "";


    @Component
    @ConfigurationPropertiesBinding
    public static class RectangleConverter implements Converter<String, BrowserWindowRect> {
        @Override
        public BrowserWindowRect convert(String windowRectangle) {
            if (Strings.isEmpty(windowRectangle)) {
                return null;
            }
            if ("MAX".equalsIgnoreCase(windowRectangle.trim())
                    || "MAXIMIZED".equalsIgnoreCase(windowRectangle.trim())) {
                return new BrowserWindowRect(0, 0, -1, -1);
            }
            List<Integer> list = Linq.from(windowRectangle.split(",")).select(p -> Integer.valueOf(p)).toList();
            return new BrowserWindowRect(list.get(0), list.get(1), list.get(2), list.get(3));
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class CrawlPageConfigConverter implements Converter<String, List<CrawlPageConfig>> {
        @Override
        public List<CrawlPageConfig> convert(String s) {
            return fromJson(s, new TypeReference<List<CrawlPageConfig>>() {
            }.getType());
        }
    }
}
