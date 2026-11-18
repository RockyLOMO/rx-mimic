package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.aqc.AqcCompanyDetailRequest;
import org.rx.crawler.task.aqc.AqcCompanyDetailResult;
import org.rx.crawler.task.aqc.AqcCompanySearchRequest;
import org.rx.crawler.task.aqc.AqcCompanySearchResult;
import org.rx.crawler.task.aqc.AqcConfig;
import org.rx.crawler.task.aqc.AqcMallTask;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.exception.InvalidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class AqcMallTaskTests {

    private AqcMallTask newTask(AppConfig config) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new AqcMallTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), objectMapper);
    }

    @Test
    public void aqcConfigShouldPointToAiqicha() {
        AppConfig config = new AppConfig();
        AqcConfig aqc = config.getCustom().getAqc();

        assertEquals("common", aqc.getProfileName());
        assertEquals("https://aiqicha.baidu.com", aqc.getHomeUrl());
        assertEquals("https://aiqicha.baidu.com/s?q={keyword}&t=0", aqc.getSearchUrlTemplate());
        assertEquals("https://aiqicha.baidu.com/company_detail_{pid}", aqc.getDetailUrlTemplate());
        assertEquals("https://passport.baidu.com", aqc.getLoginUrlPrefix());
        assertEquals(20, aqc.getDefaultMaxResults());
        assertEquals(50, aqc.getMaxResultsLimit());
        assertEquals(120, aqc.getSecurityHumanWaitSeconds());
        assertEquals("./data/aqc/debug", aqc.getDebugOutputDir());
    }

    @Test
    public void aqcTaskTypeShouldBeCompanySearch() {
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);

        assertEquals("aqcCompanySearch", newTask(config).taskType());
    }

    @Test
    public void aqcPageUrlShouldBeRecognized() {
        AqcMallTask task = newTask(new AppConfig());

        assertTrue(task.isAqcPageUrl("https://aiqicha.baidu.com/s?q=%E9%98%BF%E9%87%8C"));
        assertTrue(task.isAqcPageUrl("https://aiqicha.baidu.com/company_detail_2844838"));
        assertFalse(task.isAqcPageUrl("https://passport.baidu.com/v2/?login"));
        assertFalse(task.isAqcPageUrl(null));
    }

    @Test
    public void loginBodyShouldRequireTwoStrongSignals() {
        AqcMallTask task = newTask(new AppConfig());

        assertTrue(task.looksLikeAqcLoginBody("扫码登录\n账号登录\n忘记密码"));
        assertTrue(task.looksLikeAqcLoginBody("短信验证码登录\n百度用户协议"));
        assertFalse(task.looksLikeAqcLoginBody("登录\n注册\n首页")); // 单一弱特征不算登录墙
        assertFalse(task.looksLikeAqcLoginBody(""));
        assertFalse(task.looksLikeAqcLoginBody(null));
    }

    @Test
    public void searchRequestShouldRequireKeyword() {
        AqcCompanySearchRequest request = new AqcCompanySearchRequest();
        assertFalse(request.isKeywordPresent());
        request.setKeyword("  ");
        assertFalse(request.isKeywordPresent());
        request.setKeyword("阿里巴巴");
        assertTrue(request.isKeywordPresent());
    }

    @Test
    public void detailRequestShouldRequirePidOrKeyword() {
        AqcCompanyDetailRequest request = new AqcCompanyDetailRequest();
        assertFalse(request.isPidOrKeywordPresent());
        request.setPid("2844838");
        assertTrue(request.isPidOrKeywordPresent());
        request.setPid(null);
        request.setKeyword("91330100MA27H4F86K");
        assertTrue(request.isPidOrKeywordPresent());
    }

    @Test
    public void detailRequestPidMustBeNumeric() {
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        AqcMallTask task = newTask(config);

        AqcCompanyDetailRequest bad = new AqcCompanyDetailRequest();
        bad.setPid("abc<script>");
        assertThrows(InvalidException.class, () -> task.companyDetail(bad));
    }

    /**
     * 真实集成：keyword 自动匹配 + 详情抓取。
     * 运行：mvn test -Dtest=AqcMallTaskTests -Daqc.detail.integration=true（需本机 Chrome）
     */
    @Test
    public void detailByKeywordIntegration() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("aqc.detail.integration", "false")));
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        AqcMallTask task = newTask(config);

        AqcCompanyDetailRequest request = new AqcCompanyDetailRequest();
        request.setKeyword("杭州阿里巴巴音乐科技有限公司");
        AqcCompanyDetailResult result = task.companyDetail(request);

        assertNotNull(result);
        System.out.println("status=" + result.getStatus() + ", company=" + result.getCompanyName()
                + ", creditCode=" + result.getCreditCode() + ", legalPerson=" + result.getLegalPerson()
                + ", shareholders=" + result.getShareholders().size()
                + ", staff=" + result.getStaff().size()
                + ", message=" + result.getMessage());
        assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
        assertTrue(result.getCompanyName().contains("阿里巴巴"));
        assertFalse(result.getCreditCode().isEmpty());
        assertFalse(result.getLegalPerson().isEmpty());
    }

    /**
     * 真实集成：搜索候选列表。
     * 运行：mvn test -Dtest=AqcMallTaskTests -Daqc.search.integration=true（需本机 Chrome）
     */
    @Test
    public void searchByKeywordIntegration() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("aqc.search.integration", "false")));
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().setDebugEnabled(true);
        AqcMallTask task = newTask(config);

        AqcCompanySearchRequest request = new AqcCompanySearchRequest();
        request.setKeyword("阿里巴巴");
        request.setMaxResults(5);
        AqcCompanySearchResult result = task.search(request);

        assertNotNull(result);
        System.out.println("status=" + result.getStatus() + ", count=" + result.getResultCount()
                + ", first=" + (result.getItems().isEmpty() ? "" : result.getItems().get(0).getCompanyName())
                + ", message=" + result.getMessage());
        System.out.println("diagnostics=" + result.getDiagnostics());
        assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
        assertFalse(result.getItems().isEmpty());
        assertTrue(result.getItems().get(0).getPid().matches("\\d+"));
    }
}
