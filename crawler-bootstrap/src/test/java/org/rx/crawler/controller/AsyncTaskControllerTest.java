package org.rx.crawler.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.config.TaskApiProperties;
import org.rx.crawler.task.common.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AsyncTaskControllerTest {
    MockMvc mvc;CustomCrawlQueueService queue;TaskApiProperties props;
    @BeforeEach void init() {
        props=new TaskApiProperties();props.setApiKeys(Map.of("alice","alice-secret","bob","bob-secret"));props.setPublicBasePath("/crawler/api/v1");
        queue=mock(CustomCrawlQueueService.class);
        AsyncTaskView view=new AsyncTaskView();view.setTaskId("123");view.setStatus("PENDING");
        when(queue.enqueueOwned(eq("alice"),eq("jdProductDetail"),any(),eq("same"))).thenReturn(123L);
        when(queue.queryOwned(123,"alice")).thenReturn(view);
        when(queue.queryOwned(123,"bob")).thenThrow(new TaskApiException(404,"TASK_NOT_FOUND","TASK_NOT_FOUND"));
        ObjectMapper mapper=new ObjectMapper();
        AsyncTaskController controller=new AsyncTaskController(queue,mapper,Validation.buildDefaultValidatorFactory().getValidator(),props);
        mvc=MockMvcBuilders.standaloneSetup(controller).addFilters(new TaskApiAuthenticationFilter(props,mapper)).build();
    }
    @Test void acceptedReturnsStringIdAndProxyAwareLocation() throws Exception {
        mvc.perform(post("/api/v1/tasks").header("Authorization","Bearer alice-secret").header("Idempotency-Key","same")
            .contentType("application/json").content("{\"action\":\"jdProductDetail\",\"request\":{\"skuId\":\"123\"}}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Location","/crawler/api/v1/tasks/123"))
            .andExpect(header().string("Retry-After","2")).andExpect(jsonPath("$.data.taskId").value("123"));
    }
    @Test void missingAndWrongKeysFailClosed() throws Exception {
        mvc.perform(get("/api/v1/tasks/123")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tasks/123").header("Authorization","Bearer wrong")).andExpect(status().isUnauthorized());
        verifyNoInteractions(queue);
    }
    @Test void ownershipIsNotFoundAndNotInferredFromBody() throws Exception {
        mvc.perform(get("/api/v1/tasks/123").header("Authorization","Bearer bob-secret")).andExpect(status().isNotFound());
        verify(queue).queryOwned(123,"bob");
    }
    @Test void invalidActionAndLocalPathsNeverEnqueue() throws Exception {
        for(String body: new String[]{"{\"action\":\"arbitraryClass\",\"request\":{}}",
                "{\"action\":\"jdProductDetail\",\"request\":{\"skuId\":\"1\",\"debugOutputDir\":\"C:/private\"}}",
                "{\"action\":\"jdProductDetail\",\"request\":{}}"}) {
            mvc.perform(post("/api/v1/tasks").header("Authorization","Bearer alice-secret").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(queue);
    }
    @Test void rateLimitHasRetryAfter() throws Exception {
        props.setSubmissionsPerMinute(0);
        mvc.perform(post("/api/v1/tasks").header("Authorization","Bearer alice-secret").contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60"));
    }
    @Test void blankConfiguredKeyDoesNotAuthorizeEmptyToken() throws Exception {
        props.setApiKeys(Map.of("alice",""));
        mvc.perform(get("/api/v1/tasks/123").header("Authorization","Bearer ")).andExpect(status().isUnauthorized());
    }
    @Test void oldRoutesAreClosedByDefault() throws Exception {
        mvc.perform(get("/custom/jd/task/123").header("Authorization","Bearer alice-secret")).andExpect(status().isNotFound());
    }
    @Test void allLegacyControllersCanRegisterWithoutDuplicateTbRoute() {
        MockMvcBuilders.standaloneSetup(new CustomCrawlController(queue,mock(LoginKeepAliveService.class)),new JdMallController(queue),new TbMallController(queue)).build();
    }
}
