package me.bechberger.jstall.util.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves API keys from various sources with precedence:
 * 1. ./.gaw file in current directory
 * 2. ~/.gaw file in home directory
 * 3. ANSWERING_MACHINE_APIKEY environment variable
 */
public class ApiKeyResolver {

    private static final String GAW_FILENAME = ".gaw";
    private static final String ENV_VAR_NAME = "ANSWERING_MACHINE_APIKEY";

    /**
     * Resolves the API key from available sources.
     *
     * @return The API key
     * @throws ApiKeyNotFoundException if no API key is found
     */
    public static String resolve() throws ApiKeyNotFoundException {
        // 1. Try current directory
        String currentDir = System.getProperty("user.dir");
        if (currentDir != null) {
            Path currentDirKey = Paths.get(currentDir, GAW_FILENAME);
            if (Files.exists(currentDirKey)) {
                try {
                    String key = Files.readString(currentDirKey).trim();
                    if (!key.isEmpty()) {
                        return key;
                    }
                } catch (IOException e) {
                    // Continue to next source
                }
            }
        }

        // 2. Try home directory
        String home = System.getProperty("user.home");
        if (home != null) {
            Path homeDirKey = Paths.get(home, GAW_FILENAME);
            if (Files.exists(homeDirKey)) {
                try {
                    String key = Files.readString(homeDirKey).trim();
                    if (!key.isEmpty()) {
                        return key;
                    }
                } catch (IOException e) {
                    // Continue to next source
                }
            }
        }

        // 3. Try environment variable
        String envKey = System.getenv(ENV_VAR_NAME);
        if (envKey != null && !envKey.trim().isEmpty()) {
            return envKey.trim();
        }

        throw new ApiKeyNotFoundException(
            "API key not found. Please create a .gaw file in the current directory or home directory, " +
            "or set the " + ENV_VAR_NAME + " environment variable."
        );
    }

    /**
     * Exception thrown when API key cannot be resolved.
     */
    public static class ApiKeyNotFoundException extends Exception {
        public ApiKeyNotFoundException(String message) {
            super(message);
        }
    }
}