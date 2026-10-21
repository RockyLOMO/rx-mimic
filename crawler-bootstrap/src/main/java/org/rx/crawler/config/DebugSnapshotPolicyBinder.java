package org.rx.crawler.config;

import jakarta.annotation.PostConstruct;
import org.rx.crawler.task.common.DebugSnapshotPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DebugSnapshotPolicyBinder {
    @Value("${app.custom.debugSnapshots.retention-hours:72}")
    private int retentionHours;
    @Value("${app.custom.debugSnapshots.max-bytes:536870912}")
    private long maxBytes;
    @Value("${app.custom.debugSnapshots.min-free-bytes:1073741824}")
    private long minFreeBytes;
    @Value("${app.custom.debugSnapshots.active-minutes:15}")
    private int activeMinutes;

    @PostConstruct
    void bind() {
        DebugSnapshotPolicy.configure(retentionHours, maxBytes, minFreeBytes, activeMinutes);
    }
}
