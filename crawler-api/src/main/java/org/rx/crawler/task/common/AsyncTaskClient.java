package org.rx.crawler.task.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/** HTTPS + JSON client. A local wait timeout never cancels a durable task. */
public final class AsyncTaskClient {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED","FAILED","CANCELLED","TIMED_OUT");
    private final URI baseUri;
    private final String apiKey;
    private final HttpClient http;
    private final Duration requestTimeout;

    public AsyncTaskClient(URI baseUri, String apiKey) {
        this(baseUri,apiKey,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(),Duration.ofSeconds(20));
    }
    public AsyncTaskClient(URI baseUri,String apiKey,HttpClient http,Duration requestTimeout) {
        if (!("https".equalsIgnoreCase(baseUri.getScheme()) || ("http".equalsIgnoreCase(baseUri.getScheme())
                && Set.of("localhost","127.0.0.1","[::1]").contains(baseUri.getHost()))))
            throw new IllegalArgumentException("Use HTTPS outside loopback");
        if(baseUri.getQuery()!=null || baseUri.getFragment()!=null || baseUri.getUserInfo()!=null)
            throw new IllegalArgumentException("Base URI must not contain credentials, query or fragment");
        this.baseUri=URI.create(baseUri.toString().replaceAll("/+$","")+"/");
        this.apiKey=Objects.requireNonNull(apiKey);this.http=Objects.requireNonNull(http);
        if(requestTimeout.isNegative() || requestTimeout.isZero())throw new IllegalArgumentException("Positive request timeout required");
        this.requestTimeout=requestTimeout;
    }
    public JSONObject submit(String action,Object request,String idempotencyKey) throws IOException,InterruptedException {
        JSONObject body=new JSONObject();body.put("action",action);body.put("request",request);
        return send("POST","tasks",JSON.toJSONString(body),idempotencyKey,requestTimeout).body;
    }
    public JSONObject status(String taskId) throws IOException,InterruptedException {
        return send("GET",path(taskId),null,null,requestTimeout).body;
    }
    public JSONObject result(String taskId) throws IOException,InterruptedException {
        return send("GET",path(taskId)+"/result",null,null,requestTimeout).body;
    }
    public JSONObject cancel(String taskId) throws IOException,InterruptedException {
        return send("POST",path(taskId)+"/cancel","{}",null,requestTimeout).body;
    }
    public JSONObject resume(String taskId) throws IOException,InterruptedException {
        return send("POST",path(taskId)+"/resume","{}",null,requestTimeout).body;
    }
    /** Returns the terminal status envelope; result() retrieves the platform DTO separately. */
    public JSONObject await(String taskId,Duration timeout) throws IOException,InterruptedException,TimeoutException {
        if(timeout.isNegative() || timeout.isZero())throw new TimeoutException("Local task wait timed out: "+taskId);
        long deadline=System.nanoTime()+timeout.toNanos();
        int polls=0;
        while(true) {
            long left=deadline-System.nanoTime();
            if(left<=0)throw new TimeoutException("Local task wait timed out: "+taskId);
            Duration budget=Duration.ofNanos(Math.min(left,requestTimeout.toNanos()));
            Reply reply;
            try { reply=send("GET",path(taskId),null,null,budget); }
            catch(java.net.http.HttpTimeoutException e) {
                if(budget.compareTo(requestTimeout)<0)throw new TimeoutException("Local task wait timed out: "+taskId);
                throw e;
            }
            JSONObject data=reply.body.getJSONObject("data");
            if(data!=null && TERMINAL.contains(data.getString("status")))return reply.body;
            left=deadline-System.nanoTime();
            if(left<=0)throw new TimeoutException("Local task wait timed out: "+taskId);
            long delay=Math.max(reply.retryAfterSeconds*1000L,Math.min(5000,2000+polls++*500L));
            delay+=ThreadLocalRandom.current().nextLong(251);
            Thread.sleep(Math.min(delay,Math.max(1,left/1_000_000)));
        }
    }
    private String path(String id) {
        if(id==null || !id.matches("[0-9]{1,19}"))throw new IllegalArgumentException("Invalid task id");
        return "tasks/"+id;
    }
    private Reply send(String method,String path,String body,String key,Duration timeout) throws IOException,InterruptedException {
        HttpRequest.Builder builder=HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout)
                .header("Authorization","Bearer "+apiKey).header("Accept","application/json");
        if(key!=null)builder.header("Idempotency-Key",key);
        if(body==null)builder.GET();
        else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        JSONObject json;
        try { json=JSON.parseObject(response.body()); }
        catch(Exception e){ throw new IOException("Task API returned invalid JSON (HTTP "+response.statusCode()+")"); }
        if(response.statusCode()<200 || response.statusCode()>=300)
            throw new ApiException(response.statusCode(),json==null?"HTTP_ERROR":json.getString("code"));
        long retry=2;
        try { retry=Long.parseLong(response.headers().firstValue("Retry-After").orElse("2")); }
        catch(NumberFormatException ignored) { }
        return new Reply(json,Math.max(0,Math.min(retry,300)));
    }
    private record Reply(JSONObject body,long retryAfterSeconds) { }
    public static final class ApiException extends IOException {
        private final int statusCode;
        private final String code;
        public ApiException(int statusCode,String code){super("Task API HTTP "+statusCode+": "+code);this.statusCode=statusCode;this.code=code;}
        public int getStatusCode(){return statusCode;}
        public String getCode(){return code;}
    }
}
