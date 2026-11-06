package dev.subscout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic
@SpringBootApplication
public class SubScoutApplication {

    public static void main(String[] args) {
        prepareDataDirectory();
        SpringApplication.run(SubScoutApplication.class, args);
    }

    /** SQLite creates the database file but never its directory, and the pool opens before runners run. */
    private static void prepareDataDirectory() {
        String configured = System.getenv("SUB_SCOUT_DB_PATH");
        Path database = Path.of(configured == null || configured.isBlank() ? "./subscout.db" : configured);
        Path parent = database.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create the data directory " + parent, failure);
        }
    }
}
