package org.rx.crawler.config;

import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 独立数字商品价格库；不打开或迁移旧价格记录。 */
@Configuration
public class PriceDatabaseConfig {
    @Bean("priceDatabase")
    public EntityDatabase priceDatabase(
            @Value("${app.storage.priceDbPath:./data/product-price-db}") String configuredPath) throws IOException {
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        return new EntityDatabaseImpl(path.toString(), null);
    }
}
