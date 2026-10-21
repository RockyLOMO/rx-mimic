package org.rx.crawler.controller;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.dto.Result;
import org.rx.crawler.dto.RuntimeRelease;
import org.rx.crawler.task.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RuntimeHealthController {
    private final CustomCrawlQueueService queue;
    private final BrowserOwnerExecutor owner;
    private final DesktopAvailability desktop;
    private final LoginKeepAliveService keepAlive;
    @org.springframework.beans.factory.annotation.Autowired
    private BrowserProfileManager profiles;
    @Value("${crawler.runtime.version:${RX_MIMIC_VERSION:unknown}}")
    private String version;
    @Value("${crawler.runtime.release:${RX_MIMIC_RELEASE:0}}")
    private int release;
    @Value("${crawler.runtime.admin-key:${RX_MIMIC_ADMIN_KEY:}}")
    private String adminKey;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ConfigurableApplicationContext applicationContext;
    private final java.util.concurrent.atomic.AtomicBoolean shutdownStarted = new java.util.concurrent.atomic.AtomicBoolean();

    @PostConstruct
    public void configureGate() {
        RuntimeRelease.publish(release);
        queue.setExecutionGate(this::browserExecutionAvailable);
        keepAlive.setExecutionGate(() -> !queue.isDraining() && !queue.hasWaitingUser() && owner.isAvailable() && desktop.isAvailable());
    }

    private boolean browserExecutionAvailable() {
        if (!owner.isAvailable() || !desktop.isAvailable()) { return false; }
        if (profiles.isBrowserReady()) { return true; }
        // Dispatch also runs with an empty queue. Lazy mode must stay idle until actual work arrives.
        if (queue.hasPendingTasks()) { profiles.requestWarmup(); }
        return false;
    }

    @GetMapping("/health/live")
    public Result<Map<String, Object>> live() { return Result.success(state(false)); }

    @GetMapping(value = "/internal/debug/desktop", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> desktop(HttpServletRequest request) {
        if (!authorized(request)) {
            return ResponseEntity.status(403).build();
        }
        byte[] png = DebugSnapshots.captureDesktopPng();
        if (png == null || png.length == 0) {
            log.info("Admin desktop capture empty");
            return ResponseEntity.status(503).build();
        }
        log.info("Admin desktop capture served, bytes={}", png.length);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.IMAGE_PNG).body(png);
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Result<Map<String, Object>>> ready() {
        if (profiles != null && !queue.isDraining() && desktop.isAvailable()
                && (profiles.isPreWarmEnabled() || queue.hasPendingTasks())) { profiles.requestWarmup(); }
        Map<String, Object> state = state(true);
        boolean ready = !queue.isDraining() && owner.isAvailable()
                && Boolean.TRUE.equals(state.get("browserAvailable")) && Boolean.TRUE.equals(state.get("desktopAvailable")) && Boolean.TRUE.equals(state.get("databaseAvailable"));
        state.put("ready", ready);
        return ResponseEntity.status(ready ? 200 : 503).body(ready ? Result.success(state)
                : Result.fail("NOT_READY", "Runtime is not ready to execute browser tasks", state));
    }

    @PostMapping("/internal/deployment/drain")
    public ResponseEntity<Result<Map<String, Object>>> drain(HttpServletRequest request) { return maintenance(request, true); }

    @PostMapping("/internal/deployment/resume")
    public ResponseEntity<Result<Map<String, Object>>> resume(HttpServletRequest request) { return maintenance(request, false); }

    @GetMapping("/internal/deployment/status")
    public ResponseEntity<Result<Map<String, Object>>> status(HttpServletRequest request) {
        if (!authorized(request)) { return forbidden(); }
        return ResponseEntity.ok(Result.success(deploymentState()));
    }

    @PostMapping("/internal/deployment/shutdown")
    public ResponseEntity<Result<Map<String, Object>>> shutdown(HttpServletRequest request) {
        if (!authorized(request)) { return forbidden(); }
        if (!queue.isDraining() || !queue.isIdle() || queue.hasWaitingUser() || owner.getPendingCount() != 0) {
            return ResponseEntity.status(409).body(Result.fail("NOT_DRAINED", "Drain and wait for browser tasks before shutdown", deploymentState()));
        }
        if (shutdownStarted.compareAndSet(false, true)) {
            // Give Tomcat time to flush the accepted response, then close Spring in dependency order.
            Thread shutdown = new Thread(() -> {
                try { Thread.sleep(500); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                applicationContext.close();
                System.exit(0);
            }, "deployment-shutdown");
            shutdown.setDaemon(true);
            shutdown.start();
        }
        return ResponseEntity.accepted().body(Result.success(deploymentState()));
    }

    private ResponseEntity<Result<Map<String, Object>>> forbidden() {
        return ResponseEntity.status(403).body(Result.fail("FORBIDDEN", "Admin access required", null));
    }

    private ResponseEntity<Result<Map<String, Object>>> maintenance(HttpServletRequest request, boolean draining) {
        if (!authorized(request)) { return ResponseEntity.status(403).body(Result.fail("FORBIDDEN", "Admin access required", null)); }
        if (!draining && shutdownStarted.get()) {
            return ResponseEntity.status(409).body(Result.fail("SHUTTING_DOWN", "Shutdown already started", deploymentState()));
        }
        queue.setDraining(draining);
        return ResponseEntity.ok(Result.success(deploymentState()));
    }

    private boolean authorized(HttpServletRequest request) {
        try {
            if (adminKey == null || adminKey.isBlank() || !InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) { return false; }
            String authorization = request.getHeader("Authorization");
            return authorization != null && MessageDigest.isEqual(("Bearer " + adminKey).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return false; }
    }

    private Map<String, Object> deploymentState() {
        Map<String, Object> state = state(false);
        boolean waiting = queue.hasWaitingUser();
        state.put("waitingUser", waiting);
        if (waiting) {
            state.put("idle", false);
            state.put("runningTasks", Math.max(1, (Integer) state.get("runningTasks")));
        }
        return state;
    }

    private Map<String, Object> state(boolean details) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", version);
        state.put("release", release);
        state.put("draining", queue.isDraining());
        state.put("runningTasks", Math.max(queue.getRunningCount(), owner.getPendingCount()));
        state.put("idle", queue.isIdle() && owner.getPendingCount() == 0);
        if (details) {
            state.put("browserAvailable", profiles != null && profiles.isBrowserReady());
            state.put("desktopAvailable", desktop.isAvailable());
            state.put("databaseAvailable", queue.isDatabaseAvailable());
            state.put("schedulerAvailable", owner.isAvailable());
        }
        return state;
    }
}
