package org.rx.crawler.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.crawler.FiddlerWatcher;
import org.rx.crawler.config.AppConfig;
import org.rx.io.FileWatcher;
import org.rx.io.Files;
import org.rx.net.rpc.Remoting;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;

@RequiredArgsConstructor
@Slf4j
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.fiddler-enabled", havingValue = "true", matchIfMissing = true)
public class FiddlerService implements FiddlerWatcher {
    final AppConfig config;
    FileWatcher watcher;

    @PostConstruct
    public void init() {
        createWatch();
        Remoting.register(this, config.getFiddlerListenPort(), false);

        Tasks.scheduleDaily(() -> {
            String dir = Files.concatPath(config.getBaseDir(), "fiddler/");
//            Files.delete(dir);
//            Files.createDirectory(dir);
            for (File file : Files.listFiles(dir, false)) {
                FileUtils.forceDelete(file);
            }

            createWatch();
        }, config.getCleanTaskTime());
    }

    void createWatch() {
        String dir = Files.concatPath(config.getBaseDir(), "fiddler/");
        Files.createDirectory(dir);
        watcher = new FileWatcher(dir);
        watcher.onChanged.add((sender, e) -> {
            log.info("File[{}] has {} change", e.getPath(), e.isModify());
            if (!e.isModify()) {
                return;
            }

            String filePath = e.getPath().toString();
            String filename = FilenameUtils.getName(filePath);
            String[] args = Strings.split(filename, "_", 3);
            publishEvent(EVENT_CALLBACK, new CallbackEventArgs(args[0] + "_" + args[1], Files.readLines(filePath)));
        });
    }
}
