package org.rx.crawler.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.rx.crawler.dto.Result;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
@Component
@Order(-100)
@RequiredArgsConstructor
public class TaskApiAuthenticationFilter extends OncePerRequestFilter {
    public static final String CALLER_ATTRIBUTE = TaskApiAuthenticationFilter.class.getName() + ".caller";
    private final TaskApiProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, RateWindow> rates = new HashMap<>();
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api/v1") || path.startsWith("/api/v1/") || path.equals("/custom") || path.startsWith("/custom/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/custom")) {
            String remote = request.getRemoteAddr();
            if (!properties.isLegacyEnabled() || !("127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote))
                    || request.getHeader("X-Forwarded-For") != null || request.getHeader("Forwarded") != null) {
                reject(response,404,"NOT_FOUND"); return;
            }
        }
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        String caller = null;
        for (var entry : properties.getApiKeys().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()
                    && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), entry.getValue().getBytes(StandardCharsets.UTF_8))) caller = entry.getKey();
        }
        if (caller == null) { response.setHeader("WWW-Authenticate", "Bearer"); reject(response,401,"UNAUTHORIZED"); return; }
        request.setAttribute(CALLER_ATTRIBUTE,caller);
        if ("POST".equals(request.getMethod()) && path.equals("/api/v1/tasks") && !admit(caller)) {
            response.setHeader("Retry-After", "60"); reject(response,429,"RATE_LIMITED"); return;
        }
        chain.doFilter(request,response);
    }
    private synchronized boolean admit(String caller) {
        long minute = System.currentTimeMillis()/60000;
        RateWindow window = rates.computeIfAbsent(caller, key -> new RateWindow());
        if (window.minute != minute) { window.minute=minute;window.count=0; }
        return ++window.count <= properties.getSubmissionsPerMinute();
    }
    private void reject(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(),Result.fail(code,code,null));
    }
    private static class RateWindow { long minute; int count; }
}
