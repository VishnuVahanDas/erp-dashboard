package org.ajeet.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Config lookup: process environment first, then a KEY=VALUE line in a
 * {@code .env} file, then the default. The {@code .env} is looked for in the
 * working directory, then its parent, so web-dashboard/.env (shared with
 * docker compose) is found when the backend runs from web-dashboard/backend.
 */
public final class Env {

    private static final Path[] ENV_FILES = {Path.of(".env"), Path.of("../.env")};

    private static Map<String, String> dotenv;

    private Env() {}

    public static String get(String key, String def) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = dotenv().get(key);
        }
        return (value == null || value.isBlank()) ? def : value;
    }

    private static synchronized Map<String, String> dotenv() {
        if (dotenv == null) {
            dotenv = new HashMap<>();
            for (Path envFile : ENV_FILES) {
                if (!Files.exists(envFile)) continue;
                try {
                    for (String line : Files.readAllLines(envFile)) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#")) continue;
                        int eq = s.indexOf('=');
                        if (eq > 0) {
                            dotenv.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                        }
                    }
                    System.out.println("Loaded config from " + envFile.toAbsolutePath().normalize());
                } catch (IOException e) {
                    System.err.println("Failed to read " + envFile + ": " + e.getMessage());
                }
                break;
            }
        }
        return dotenv;
    }
}
