package org.rx.crawler.task.common;
import com.sun.net.httpserver.HttpServer;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class AsyncTaskClientTest {
    HttpServer server; AsyncTaskClient client; AtomicInteger cancellations=new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/crawler/api/v1/tasks", exchange->{
            assertEquals("Bearer test-key",exchange.getRequestHeaders().getFirst("Authorization"));
            String path=exchange.getRequestURI().getPath();String json;int code=200;
            if(path.endsWith("/cancel")){cancellations.incrementAndGet();json="{\"success\":true}";}
            else if(path.endsWith("/999")){code=404;json="{\"success\":false,\"code\":\"TASK_NOT_FOUND\"}";}
            else if(path.endsWith("/result")){json="{\"success\":true,\"data\":{\"status\":\"SUCCESS\"}}";}
            else if("POST".equals(exchange.getRequestMethod())) {
                assertEquals("same",exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                JSONObject body=com.alibaba.fastjson2.JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("jdProductDetail",body.getString("action"));code=202;
                json="{\"success\":true,\"data\":{\"taskId\":\"123\",\"status\":\"PENDING\"}}";
            } else {json="{\"success\":true,\"data\":{\"taskId\":\"123\",\"status\":\"RUNNING\"}}";}
            byte[] bytes=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After","2");exchange.sendResponseHeaders(code,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        client=new AsyncTaskClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/crawler/api/v1"),"test-key");
    }
    @AfterEach void cleanup(){server.stop(0);}
    @Test void submissionPreservesPrefixAndIdempotencyKey() throws Exception {
        JSONObject reply=client.submit("jdProductDetail",Map.of("skuId","123"),"same");
        assertEquals("123",reply.getJSONObject("data").getString("taskId"));
        assertEquals("RUNNING",client.status("123").getJSONObject("data").getString("status"));
        assertEquals("SUCCESS",client.result("123").getJSONObject("data").getString("status"));
    }
    @Test void localDeadlineNeverCancelsServerTask() {
        assertThrows(TimeoutException.class,()->client.await("123",Duration.ofMillis(150)));
        assertEquals(0,cancellations.get());
    }
    @Test void errorCodeAndExplicitCancellation() throws Exception {
        AsyncTaskClient.ApiException error=assertThrows(AsyncTaskClient.ApiException.class,()->client.status("999"));
        assertEquals(404,error.getStatusCode());assertEquals("TASK_NOT_FOUND",error.getCode());
        client.cancel("123");assertEquals(1,cancellations.get());
    }
    @Test void rejectsCleartextRemoteCredentialsAndPathTraversal() {
        assertThrows(IllegalArgumentException.class,()->new AsyncTaskClient(URI.create("http://example.com/api/v1"),"secret"));
        assertThrows(IllegalArgumentException.class,()->client.status("../admin"));
    }
}
