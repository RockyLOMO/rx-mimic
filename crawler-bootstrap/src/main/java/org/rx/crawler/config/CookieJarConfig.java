package org.rx.crawler.config;

import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Files;
import java.nio.file.Path;
import org.rx.net.http.HttpClientCookieJar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CookieJarConfig {
    @Bean
    public EntityDatabase entityDatabase(@Value("${app.storage.h2DbPath:./data/browser-task-db}") String configuredPath) throws java.io.IOException {
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        return new EntityDatabaseImpl(path.toString(), null);
    }

    @Bean
    public HttpClientCookieJar httpClientCookieJar(EntityDatabase entityDatabase) {
        return HttpClientCookieJar.storage(entityDatabase);
    }
}
