package me.bechberger.jstall.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration for AI providers (Gardener or Ollama).
 *
 * Reads from .jstall-ai-config file in current directory or home directory.
 * Falls back to .gaw file for backward compatibility with Gardener API key.
 *
 * Config file format (Java properties):
 * <pre>
 * provider=ollama
 * model=qwen2.5:14b
 * ollama.host=http://127.0.0.1:11434
 * api.key=your-gardener-api-key
 * </pre>
 */
public class AiConfig {

    private static final String CONFIG_FILENAME = ".jstall-ai-config";
    private static final String GAW_FILENAME = ".gaw";

    public enum Provider {
        GARDENER,
        OLLAMA
    }

    private final Provider provider;
    private final String model;
    private final String apiKey;
    private final String ollamaHost;

    /**
     * Creates a config with explicit values.
     */
    public AiConfig(Provider provider, String model, String apiKey, String ollamaHost) {
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
        this.ollamaHost = ollamaHost;
    }

    /**
     * Loads configuration from file system.
     *
     * @return Loaded configuration
     * @throws ConfigNotFoundException if no configuration is found
     */
    public static AiConfig load() throws ConfigNotFoundException {
        // Try to load from .jstall-ai-config
        Properties props = loadConfigFile();

        if (props != null) {
            return fromProperties(props);
        }

        // Fall back to .gaw file for Gardener API key
        String gawKey = loadGawFile();
        if (gawKey != null) {
            return new AiConfig(
                Provider.GARDENER,
                "gpt-50-nano",
                gawKey,
                null
            );
        }

        // Fall back to environment variable (backward compatibility)
        String envKey = System.getenv("ANSWERING_MACHINE_APIKEY");
        if (envKey != null && !envKey.trim().isEmpty()) {
            return new AiConfig(
                Provider.GARDENER,
                "gpt-50-nano",
                envKey.trim(),
                null
            );
        }

        throw new ConfigNotFoundException(
            "AI configuration not found. Please create a " + CONFIG_FILENAME + " file in the current directory or home directory, " +
            "or create a .gaw file with your Gardener API key, " +
            "or set the ANSWERING_MACHINE_APIKEY environment variable."
        );
    }

    private static Properties loadConfigFile() {
        // Try current directory
        String currentDir = System.getProperty("user.dir");
        if (currentDir != null) {
            Path configPath = Paths.get(currentDir, CONFIG_FILENAME);
            Properties props = tryLoadProperties(configPath);
            if (props != null) {
                return props;
            }
        }

        // Try home directory
        String home = System.getProperty("user.home");
        if (home != null) {
            Path configPath = Paths.get(home, CONFIG_FILENAME);
            Properties props = tryLoadProperties(configPath);
            if (props != null) {
                return props;
            }
        }

        return null;
    }

    private static Properties tryLoadProperties(Path path) {
        if (Files.exists(path)) {
            try {
                Properties props = new Properties();
                props.load(Files.newBufferedReader(path));
                return props;
            } catch (IOException e) {
                // Continue to next source
            }
        }
        return null;
    }

    private static String loadGawFile() {
        // Try current directory
        String currentDir = System.getProperty("user.dir");
        if (currentDir != null) {
            Path gawPath = Paths.get(currentDir, GAW_FILENAME);
            String key = tryReadFile(gawPath);
            if (key != null) {
                return key;
            }
        }

        // Try home directory
        String home = System.getProperty("user.home");
        if (home != null) {
            Path gawPath = Paths.get(home, GAW_FILENAME);
            String key = tryReadFile(gawPath);
            if (key != null) {
                return key;
            }
        }

        return null;
    }

    private static String tryReadFile(Path path) {
        if (Files.exists(path)) {
            try {
                String content = Files.readString(path).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            } catch (IOException e) {
                // Continue
            }
        }
        return null;
    }

    private static AiConfig fromProperties(Properties props) {
        String providerStr = props.getProperty("provider", "gardener").toLowerCase();
        Provider provider = providerStr.equals("ollama") ? Provider.OLLAMA : Provider.GARDENER;

        String model = props.getProperty("model",
            provider == Provider.OLLAMA ? "qwen3:30b" : "gpt-50-nano");

        String apiKey = props.getProperty("api.key");
        String ollamaHost = props.getProperty("ollama.host", "http://127.0.0.1:11434");

        return new AiConfig(provider, model, apiKey, ollamaHost);
    }

    public Provider getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getOllamaHost() {
        return ollamaHost;
    }

    public boolean isGardener() {
        return provider == Provider.GARDENER;
    }

    public boolean isOllama() {
        return provider == Provider.OLLAMA;
    }

    /**
     * Exception thrown when configuration cannot be found.
     */
    public static class ConfigNotFoundException extends Exception {
        public ConfigNotFoundException(String message) {
            super(message);
        }
    }
}