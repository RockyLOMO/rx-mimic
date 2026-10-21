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
import org.springframework.service.RWebConfig;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        private String downloadPath = "./data/temp/";
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
        private PriceWatchConfig priceWatch = new PriceWatchConfig();
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

    @Data
    public static class PriceWatchConfig {
        private boolean enabled = true;
        private String timezone = "Asia/Shanghai";
        private String windowStart = "06:00";
        /** 次日这个时刻起不再准入。跨日窗口，不是同一天的结束时刻。 */
        private String windowEnd = "01:00";
        /** 每个平台每个业务日最多访问多少个商品。未配置的平台不访问。业务日从 windowStart 起算。 */
        private Map<String, Integer> dailyMaxProductVisits = defaultVisits();
        /** 同一平台一个业务日内，登录失效或滑动验证失败达到该次数后，停止该平台后续商品访问。0 表示不启用。 */
        private int dailyMaxLoginOrSliderFailures = 3;
        private int activeWatchLimit = 30;
        private int backgroundQueuedLimit = 2;
        private BigDecimal dropAmount = new BigDecimal("1.00");
        private BigDecimal dropPercent = new BigDecimal("3");
        private int reviewDelayMinutes = 15;
        private int snapshotFreshMinutes = 30;
        private int notifyStartHour = 8;
        private int notifyEndHour = 23;
        private boolean notifyEnabled = false;
        private int ordinaryIntervalHours = 10;
        private int focusIntervalHours = 4;
        private int coldIntervalHours = 48;
        private int maxIdentityAttempts = 3;
        private PriceBroadcastConfig broadcast = new PriceBroadcastConfig();

        private static Map<String, Integer> defaultVisits() {
            Map<String, Integer> visits = new LinkedHashMap<String, Integer>();
            visits.put("jd", 100);
            visits.put("tb", 100);
            return visits;
        }
    }

    @Data
    public static class PriceBroadcastConfig {
        private boolean enabled = false;
        private int workerThreads = 2;
        private int httpTimeoutSeconds = 5;
        private int retentionDays = 7;
        private List<PriceSubscriberConfig> subscribers = new ArrayList<PriceSubscriberConfig>();
    }

    @Data
    public static class PriceSubscriberConfig {
        private String id;
        private List<String> ownerIds = new ArrayList<String>();
        private String url;
        private String signingSecretEnv;
        private String signingSecret;

        public String resolveSecret() {
            if (signingSecretEnv != null && !signingSecretEnv.isBlank()) {
                String envVal = System.getenv(signingSecretEnv);
                if (envVal != null && !envVal.isBlank()) {
                    return envVal.trim();
                }
            }
            return signingSecret != null ? signingSecret.trim() : "";
        }

        public boolean matchesOwner(String ownerId) {
            if (ownerIds == null || ownerIds.isEmpty()) {
                return false;
            }
            if (ownerIds.contains("*")) {
                return true;
            }
            return ownerId != null && ownerIds.contains(ownerId);
        }
    }

    private BrowserPoolConfig browser = new BrowserPoolConfig();
    private CustomTaskConfig custom = new CustomTaskConfig();

    private int fiddlerListenPort;
    private String cleanTaskTime = "03:00:00";
    private String baseDir = "./data";

    @PostConstruct
    public void init() {
        RWebConfig.enableTrace(null);
    }


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
