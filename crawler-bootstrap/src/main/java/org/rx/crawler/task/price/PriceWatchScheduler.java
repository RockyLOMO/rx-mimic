package org.rx.crawler.task.price;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PriceWatchScheduler {
    private final PriceWatchCoordinator coordinator;

    @Scheduled(initialDelay = 20000, fixedDelay = 30000)
    public void tick() {
        coordinator.tick();
    }
}
