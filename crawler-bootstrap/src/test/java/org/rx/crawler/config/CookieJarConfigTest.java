package org.rx.crawler.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CookieJarConfigTest {
    @TempDir Path directory;

    @Test
    void configuredDatabasePathKeepsDataAcrossDatabaseInstances() throws Exception {
        Path databasePath = directory.resolve("persistent").resolve("queue");
        CookieJarConfig config = new CookieJarConfig();
        try (var database = config.entityDatabase(databasePath.toString())) {
            database.executeUpdate("create table path_verification (id int primary key)");
            database.executeUpdate("insert into path_verification values (7)");
        }
        assertTrue(Files.exists(Path.of(databasePath + ".mv.db")));
        try (var database = config.entityDatabase(databasePath.toString())) {
            int count = database.withConnection(connection -> {
                try (var statement = connection.createStatement(); var results = statement.executeQuery("select count(*) from path_verification where id=7")) {
                    results.next();
                    return results.getInt(1);
                }
            });
            assertEquals(1, count);
        }
    }
}
