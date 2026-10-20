package org.rx.crawler.task.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingEventArgs;
import org.rx.net.transport.TcpServer;
import org.rx.util.BeanMapper;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.List;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomCrawlRemotingService implements CustomCrawlRemotingContract {
    private final AppConfig appConfig;
    @org.springframework.beans.factory.annotation.Autowired private CustomCrawlQueueService queue;
    @org.springframework.beans.factory.annotation.Autowired private BrowserOwnerExecutor ownerExecutor;
    private final JdUnionPromotionTask jdUnionPromotionTask;
    private final TbPromotionOrdersTask tbPromotionOrdersTask;
    private final TbPromotionUrlTask tbPromotionUrlTask;
    private final BrowserProfileManager profileManager;
    private final HttpClientCookieJar cookieJar;
    private TcpServer remotingServer;

    @PostConstruct
    public void init() {
        if (!appConfig.getCustom().isRemotingEnabled() || appConfig.getCustom().getRemotingListenPort() <= 0) {
            return;
        }
        try {
            remotingServer = Remoting.register(this, appConfig.getCustom().getRemotingListenPort(), false);
            log.info("Custom crawl remoting listen on {}", appConfig.getCustom().getRemotingListenPort());
        } catch (Exception e) {
            log.warn("Custom crawl remoting register fail, port={}, error={}",
                    appConfig.getCustom().getRemotingListenPort(), e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        tryClose(remotingServer);
    }

    @Override
    public PromotionUrlResult getJdPromotionUrl(PromotionUrlRequest request) {
        PromotionUrlResult result = queue.submitAndWait("getPromotionUrl", request, PromotionUrlResult.class);
        publishDirect(EVENT_JD_PROMOTION_RESULT, result);
        return result;
    }

    @Override
    public List<PromotionUrlResult> getJdPromotionUrls(List<String> keywords) {
        List<PromotionUrlResult> results = queue.submitAndWaitJdPromotionUrls(keywords);
        publishDirect(EVENT_JD_PROMOTION_URLS_RESULT, results);
        return results;
    }

    @Override
    public JdUnionPromotionOrdersResult getJdPromotionOrders(JdUnionPromotionOrdersRequest request) {
        JdUnionPromotionOrdersResult result = queue.submitAndWaitOrders("getPromotionOrders", request);
        publishDirect(EVENT_JD_PROMOTION_ORDERS_RESULT, result);
        return result;
    }

    @Override
    public TbPromotionOrdersResult getTbPromotionOrders(TbPromotionOrdersRequest request) {
        TbPromotionOrdersResult result = queue.submitAndWaitTbOrders("getTbPromotionOrders", request);
        publishDirect(EVENT_TB_PROMOTION_ORDERS_RESULT, result);
        return result;
    }

    @Override
    public PromotionUrlResult getTbPromotionUrl(PromotionUrlRequest request) {
        PromotionUrlResult result = queue.submitAndWaitTbPromotionUrl("getTbPromotionUrl", request);
        publishDirect(EVENT_TB_PROMOTION_URL_RESULT, result);
        return result;
    }

    @Override
    public List<PromotionUrlResult> getTbPromotionUrls(List<String> keywords) {
        List<PromotionUrlResult> results = queue.submitAndWaitTbPromotionUrls(keywords);
        publishDirect(EVENT_TB_PROMOTION_URLS_RESULT, results);
        return results;
    }

    @Override
    public PromotionUrlResult loginCheck(PromotionUrlRequest request) {
        PromotionUrlResult result = queue.submitAndWait("loginCheck", request, PromotionUrlResult.class);
        publishDirect(EVENT_JD_PROMOTION_RESULT, result);
        return result;
    }

    @Override
    public String cookiesRaw(String profileName, String url) {
        return profileManager == null ? cookiesRawOnOwner(profileName, url)
                : ownerExecutor.call(() -> cookiesRawOnOwner(profileName, url));
    }

    private String cookiesRawOnOwner(String profileName, String url) {
        if (Strings.isEmpty(url)) {
            return Strings.EMPTY;
        }
        try {
            if (profileManager != null) {
                String resolvedProfileName = profileManager.normalizeProfileName(
                        Strings.isEmpty(profileName) ? profileManager.defaultProfileName() : profileName);
                BrowserProfileManager.ProfileLease lease = null;
                try {
                    WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
                    lease = profileManager.acquire(resolvedProfileName, config);
                    lease.getBrowser().setCookieRegion(resolvedProfileName);
                    String rawCookie = ifNull(lease.getBrowser().getRawCookie(url), Strings.EMPTY);
                    if (lease.isFromSession()) {
                        lease.keepOpen(300);
                    }
                    return rawCookie;
                } finally {
                    tryClose(lease);
                }
            }
            return ifNull(cookieJar.loadForRequest(URI.create(url)), Strings.EMPTY);
        } catch (Exception e) {
            log.warn("load raw cookie fail, profile={}, url={}, error={}", profileName, url, e.getMessage());
            return Strings.EMPTY;
        }
    }

    @Override
    public boolean closeProfile(String profileName) {
        return queue.closeProfile(profileName);
    }

    private void publishDirect(String eventName, Object result) {
        try {
            publishEvent(eventName, RemotingEventArgs.direct(result));
        } catch (Exception e) {
            log.debug("Ignore direct event publish outside remoting context: {}", e.getMessage());
        }
    }
}
