package me.bechberger.jstall.util.llm;

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

    /**
     * Think mode for Ollama.
     *
     * <p>Ollama supports a boolean think flag for many models. The model "gpt-oss" requires
     * a string think mode ("low", "medium", "high"); passing true/false is ignored.
     */
    public enum OllamaThinkMode {
        OFF,
        LOW,
        MEDIUM,
        HIGH
    }

    private final Provider provider;
    private final String model;
    private final String apiKey;
    private final String ollamaHost;
    private final OllamaThinkMode ollamaThinkMode;

    /**
     * Creates a config with explicit values.
     */
    public AiConfig(Provider provider, String model, String apiKey, String ollamaHost) {
        this(provider, model, apiKey, ollamaHost, null);
    }

    /**
     * Creates a config with explicit values.
     */
    public AiConfig(Provider provider, String model, String apiKey, String ollamaHost, OllamaThinkMode ollamaThinkMode) {
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
        this.ollamaHost = ollamaHost;
        this.ollamaThinkMode = ollamaThinkMode;
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

        boolean isGptOss = model != null && model.toLowerCase().startsWith("gpt-oss");

        // Ollama think mode:
        // - For most models: handled as boolean (OFF => think=false; otherwise think=true)
        // - For model gpt-oss*: prefers string think: low|medium|high
        //   Additionally, we map:
        //     true  -> high
        //     false -> low
        //   (this exists because gpt-oss ignores boolean think values)
        // Default: ON/high
        String thinkStr = props.getProperty("ollama.think");
        OllamaThinkMode thinkMode;
        if (thinkStr == null) {
            thinkMode = OllamaThinkMode.HIGH;
        } else {
            thinkMode = switch (thinkStr.trim().toLowerCase()) {
                case "false", "0" -> isGptOss ? OllamaThinkMode.LOW : OllamaThinkMode.OFF;
                case "true", "1" -> OllamaThinkMode.HIGH;
                case "off", "none" -> OllamaThinkMode.OFF;
                case "low" -> OllamaThinkMode.LOW;
                case "medium" -> OllamaThinkMode.MEDIUM;
                case "high" -> OllamaThinkMode.HIGH;
                default -> throw new IllegalArgumentException("Invalid ollama.think value: " + thinkStr);
            };
        }

        return new AiConfig(provider, model, apiKey, ollamaHost, thinkMode);
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

    /**
     * Returns configured ollama think mode (nullable if not configured).
     */
    public OllamaThinkMode getOllamaThinkMode() {
        return ollamaThinkMode;
    }

    /**
     * Returns effective think mode based on model.
     *
     * <p>Note: since 0.4.3+ we default to HIGH (thinking enabled) unless configured.
     */
    public OllamaThinkMode getEffectiveOllamaThinkMode() {
        if (ollamaThinkMode != null) {
            return ollamaThinkMode;
        }
        return OllamaThinkMode.HIGH;
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