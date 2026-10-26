package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.PromotionUrlRequest;
import org.rx.crawler.task.common.PromotionUrlResult;
import org.rx.crawler.task.jd.JdMallTask;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.tb.TbMallTask;
import org.rx.crawler.task.tb.TbProductDetailRequest;
import org.rx.crawler.task.tb.TbProductDetailResult;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CustomCrawlQueueServiceTest {
    @Test
    public void submitAndWaitShouldPersistAndExecuteTask() throws Exception {
        EntityDatabase entityDatabase = new EntityDatabaseImpl("jdbc:h2:mem:queue-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig appConfig = new AppConfig();
        AppConfig.CustomTaskConfig customTaskConfig = new AppConfig.CustomTaskConfig();
        customTaskConfig.setQueueMaxConcurrency(1);
        customTaskConfig.setQueueTimeoutSeconds(5);
        appConfig.setCustom(customTaskConfig);

        JdUnionPromotionTask task = mock(JdUnionPromotionTask.class);
        when(task.getPromotionUrl(any())).thenAnswer(invocation -> {
            PromotionUrlResult result = new PromotionUrlResult();
            result.setStatus(CustomCrawlStatus.SUCCESS);
            result.setMessage("");
            result.setPromotionUrl("https://u.jd.com/test");
            return result;
        });

        CustomCrawlQueueService service = new CustomCrawlQueueService(entityDatabase, objectMapper, appConfig, task,
                mock(JdMallTask.class), mock(TbPromotionOrdersTask.class), mock(TbPromotionUrlTask.class), mock(TbMallTask.class));
        try {
            service.init();

            PromotionUrlRequest request = new PromotionUrlRequest();
            request.setKeyword("1001");
            request.setAdSiteName("2002");
            PromotionUrlResult result = service.submitAndWait("getPromotionUrl", request, PromotionUrlResult.class);

            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
            verify(task).getPromotionUrl(any());
        } finally {
            service.destroy();
            entityDatabase.close();
        }
    }

    @Test
    public void submitAndWaitShouldReturnFailedResultWhenTaskThrows() throws Exception {
        EntityDatabase entityDatabase = new EntityDatabaseImpl("jdbc:h2:mem:queue-test-fail;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig appConfig = new AppConfig();
        AppConfig.CustomTaskConfig customTaskConfig = new AppConfig.CustomTaskConfig();
        customTaskConfig.setQueueMaxConcurrency(1);
        customTaskConfig.setQueueTimeoutSeconds(5);
        appConfig.setCustom(customTaskConfig);

        JdUnionPromotionTask task = mock(JdUnionPromotionTask.class);
        when(task.getPromotionUrl(any())).thenThrow(new IllegalStateException("boom"));

        CustomCrawlQueueService service = new CustomCrawlQueueService(entityDatabase, objectMapper, appConfig, task,
                mock(JdMallTask.class), mock(TbPromotionOrdersTask.class), mock(TbPromotionUrlTask.class), mock(TbMallTask.class));
        try {
            service.init();

            PromotionUrlRequest request = new PromotionUrlRequest();
            request.setKeyword("1001");
            request.setAdSiteName("2002");
            PromotionUrlResult result = service.submitAndWait("getPromotionUrl", request, PromotionUrlResult.class);

            assertEquals(CustomCrawlStatus.FAILED, result.getStatus());
        } finally {
            service.destroy();
            entityDatabase.close();
        }
    }

    @Test
    public void submitAndWaitShouldDispatchTbPromotionUrlTask() throws Exception {
        EntityDatabase entityDatabase = new EntityDatabaseImpl("jdbc:h2:mem:queue-test-tb-url;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig appConfig = new AppConfig();
        AppConfig.CustomTaskConfig customTaskConfig = new AppConfig.CustomTaskConfig();
        customTaskConfig.setQueueMaxConcurrency(1);
        customTaskConfig.setQueueTimeoutSeconds(5);
        appConfig.setCustom(customTaskConfig);

        TbPromotionUrlTask tbPromotionUrlTask = mock(TbPromotionUrlTask.class);
        when(tbPromotionUrlTask.getPromotionUrl(any())).thenAnswer(invocation -> {
            PromotionUrlResult result = new PromotionUrlResult();
            result.setStatus(CustomCrawlStatus.SUCCESS);
            result.setMessage("");
            result.setPromotionUrl("https://m.tb.cn/test");
            return result;
        });

        CustomCrawlQueueService service = new CustomCrawlQueueService(entityDatabase, objectMapper, appConfig,
                mock(JdUnionPromotionTask.class), mock(JdMallTask.class), mock(TbPromotionOrdersTask.class), tbPromotionUrlTask, mock(TbMallTask.class));
        try {
            service.init();

            PromotionUrlRequest request = new PromotionUrlRequest();
            request.setKeyword("西麦纯燕麦片3kg");
            request.setAdSiteName("0");
            PromotionUrlResult result = service.submitAndWaitTbPromotionUrl("getTbPromotionUrl", request);

            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
            verify(tbPromotionUrlTask).getPromotionUrl(any());
        } finally {
            service.destroy();
            entityDatabase.close();
        }
    }

    @Test
    public void submitAndWaitShouldDispatchJdPromotionUrlsTask() throws Exception {
        EntityDatabase entityDatabase = new EntityDatabaseImpl("jdbc:h2:mem:queue-test-jd-urls;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig appConfig = new AppConfig();
        AppConfig.CustomTaskConfig customTaskConfig = new AppConfig.CustomTaskConfig();
        customTaskConfig.setQueueMaxConcurrency(1);
        customTaskConfig.setQueueTimeoutSeconds(5);
        appConfig.setCustom(customTaskConfig);

        JdUnionPromotionTask task = mock(JdUnionPromotionTask.class);
        when(task.getPromotionUrl(any())).thenAnswer(invocation -> {
            PromotionUrlRequest request = invocation.getArgument(0);
            PromotionUrlResult result = new PromotionUrlResult();
            result.setKeyword(request.getKeyword());
            result.setStatus(CustomCrawlStatus.SUCCESS);
            result.setPromotionUrl("https://u.jd.com/test");
            return result;
        });

        CustomCrawlQueueService service = new CustomCrawlQueueService(entityDatabase, objectMapper, appConfig, task,
                mock(JdMallTask.class), mock(TbPromotionOrdersTask.class), mock(TbPromotionUrlTask.class), mock(TbMallTask.class));
        try {
            service.init();

            List<PromotionUrlResult> results = service.submitAndWaitJdPromotionUrls(
                    Arrays.asList("100059484008", "100002715968"));

            assertEquals(2, results.size());
            assertEquals("100059484008", results.get(0).getKeyword());
            verify(task).getPromotionUrl(org.mockito.ArgumentMatchers.argThat(request -> "100059484008".equals(request.getKeyword())));
            verify(task).getPromotionUrl(org.mockito.ArgumentMatchers.argThat(request -> "100002715968".equals(request.getKeyword())));
        } finally {
            service.destroy();
            entityDatabase.close();
        }
    }

    @Test
    public void submitAndWaitShouldDispatchTbPromotionUrlsTask() throws Exception {
        EntityDatabase entityDatabase = new EntityDatabaseImpl("jdbc:h2:mem:queue-test-tb-urls;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig appConfig = new AppConfig();
        AppConfig.CustomTaskConfig customTaskConfig = new AppConfig.CustomTaskConfig();
        customTaskConfig.setQueueMaxConcurrency(1);
        customTaskConfig.setQueueTimeoutSeconds(5);
        appConfig.setCustom(customTaskConfig);

        TbPromotionUrlTask tbPromotionUrlTask = mock(TbPromotionUrlTask.class);
        when(tbPromotionUrlTask.getPromotionUrl(any())).thenAnswer(invocation -> {
            PromotionUrlResult result = new PromotionUrlResult();
            result.setKeyword("西麦纯燕麦片3kg");
            result.setStatus(CustomCrawlStatus.SUCCESS);
            result.setPromotionUrl("https://m.tb.cn/test");
            return result;
        });

        CustomCrawlQueueService service = new CustomCrawlQueueService(entityDatabase, objectMapper, appConfig,
                mock(JdUnionPromotionTask.class), mock(JdMallTask.class), mock(TbPromotionOrdersTask.class), tbPromotionUrlTask, mock(TbMallTask.class));
        try {
            service.init();

            List<PromotionUrlResult> results = service.submitAndWaitTbPromotionUrls(Arrays.asList("西麦纯燕麦片3kg"));

            assertEquals(1, results.size());
            assertEquals("西麦纯燕麦片3kg", results.get(0).getKeyword());
            verify(tbPromotionUrlTask).getPromotionUrl(org.mockito.ArgumentMatchers.argThat(request -> results.get(0).getKeyword().equals(request.getKeyword())));
        } finally {
            service.destroy();
            entityDatabase.close();
        }
    }

    @Test
    public void submitAndWaitShouldDispatchTbProductDetailTask() throws Exception {
        EntityDatabase entityDatabase = new EntityDatabaseImpl("jdbc:h2:mem:queue-test-tb-detail;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig appConfig = new AppConfig();
        AppConfig.CustomTaskConfig customTaskConfig = new AppConfig.CustomTaskConfig();
        customTaskConfig.setQueueMaxConcurrency(1);
        customTaskConfig.setQueueTimeoutSeconds(5);
        appConfig.setCustom(customTaskConfig);

        TbMallTask tbMallTask = mock(TbMallTask.class);
        when(tbMallTask.productDetail(any())).thenAnswer(invocation -> {
            TbProductDetailResult result = new TbProductDetailResult();
            result.setItemId("601440653278");
            result.setStatus(CustomCrawlStatus.SUCCESS);
            result.setTitle("测试商品");
            return result;
        });

        CustomCrawlQueueService service = new CustomCrawlQueueService(entityDatabase, objectMapper, appConfig,
                mock(JdUnionPromotionTask.class), mock(JdMallTask.class), mock(TbPromotionOrdersTask.class),
                mock(TbPromotionUrlTask.class), tbMallTask);
        try {
            service.init();

            TbProductDetailRequest request = new TbProductDetailRequest();
            request.setItemId("601440653278");
            TbProductDetailResult result = service.submitAndWaitTbProductDetail("tbProductDetail", request);

            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
            assertEquals("测试商品", result.getTitle());
            verify(tbMallTask).productDetail(any());
        } finally {
            service.destroy();
            entityDatabase.close();
        }
    }
}
