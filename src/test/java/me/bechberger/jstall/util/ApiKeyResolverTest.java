package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyResolverTest {

    @Test
    void testResolveFromCurrentDirectory(@TempDir Path tempDir) throws Exception {
        // Create .gaw file in current directory
        Path gawFile = tempDir.resolve(".gaw");
        Files.writeString(gawFile, "test-api-key-123");

        // Change to temp directory and set home to non-existent to avoid picking up real .gaw
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.dir", tempDir.toString());
            System.setProperty("user.home", "/nonexistent-home-dir");
            String apiKey = ApiKeyResolver.resolve();
            assertEquals("test-api-key-123", apiKey);
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void testPrecedence(@TempDir Path tempBase) throws Exception {
        // Create separate directories for current and home
        Path currentDir = tempBase.resolve("current");
        Path homeDir = tempBase.resolve("home");
        Files.createDirectories(currentDir);
        Files.createDirectories(homeDir);

        // Create .gaw in both directories with different keys
        Path currentGaw = currentDir.resolve(".gaw");
        Files.writeString(currentGaw, "current-dir-key");

        Path homeGaw = homeDir.resolve(".gaw");
        Files.writeString(homeGaw, "home-dir-key");

        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.dir", currentDir.toString());
            System.setProperty("user.home", homeDir.toString());

            String apiKey = ApiKeyResolver.resolve();
            assertEquals("current-dir-key", apiKey, "Current directory should take precedence");
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void testThrowsWhenNotFound() {
        // Clear system properties to ensure no .gaw files are found
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.dir", "/nonexistent");
            System.setProperty("user.home", "/nonexistent");

            ApiKeyResolver.ApiKeyNotFoundException exception =
                assertThrows(ApiKeyResolver.ApiKeyNotFoundException.class, () -> {
                    ApiKeyResolver.resolve();
                });

            assertTrue(exception.getMessage().contains(".gaw"));
            assertFalse(exception.getMessage().contains("test-api-key"),
                "Exception message should not contain API keys");
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void testTrimsWhitespace(@TempDir Path tempDir) throws Exception {
        // Create .gaw file with whitespace
        Path gawFile = tempDir.resolve(".gaw");
        Files.writeString(gawFile, "  trimmed-key  \n");

        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.dir", tempDir.toString());
            System.setProperty("user.home", "/nonexistent-home-dir");
            String apiKey = ApiKeyResolver.resolve();
            assertEquals("trimmed-key", apiKey);
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void testIgnoresEmptyFile(@TempDir Path tempBase) throws Exception {
        // Create separate directories for current and home
        Path currentDir = tempBase.resolve("current");
        Path homeDir = tempBase.resolve("home");
        Files.createDirectories(currentDir);
        Files.createDirectories(homeDir);

        // Create empty .gaw in current directory
        Path currentGaw = currentDir.resolve(".gaw");
        Files.writeString(currentGaw, "   \n");

        // Create valid .gaw in home directory
        Path homeGaw = homeDir.resolve(".gaw");
        Files.writeString(homeGaw, "home-fallback-key");

        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.dir", currentDir.toString());
            System.setProperty("user.home", homeDir.toString());

            String apiKey = ApiKeyResolver.resolve();
            assertEquals("home-fallback-key", apiKey, "Should skip empty file and use fallback");
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
    }
}