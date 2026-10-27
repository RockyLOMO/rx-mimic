package org.rx.crawler.task.price;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Memory only: one daily filter, approximately 176 KiB for 100k products at 0.1% false positives. */
public final class DailyProductBloom {
    private LocalDate date;
    private BloomFilter<CharSequence> filter;

    public synchronized boolean mightContain(int platform, String productId, Instant now, ZoneId zone) {
        rotate(now, zone);
        return filter.mightContain(platform + ":" + productId);
    }

    public synchronized void mark(int platform, String productId, Instant now, ZoneId zone) {
        rotate(now, zone);
        filter.put(platform + ":" + productId);
    }

    private void rotate(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        if (!today.equals(date)) {
            filter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 100_000, 0.001);
            date = today;
        }
    }
}
