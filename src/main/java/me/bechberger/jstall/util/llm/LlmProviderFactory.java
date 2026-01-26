package me.bechberger.jstall.util.llm;

/**
 * Central place for selecting and configuring LLM providers.
 *
 * <p>This keeps CLI commands small and avoids duplicating the same "load config -> choose provider -> choose model" logic.
 */
public final class LlmProviderFactory {

    private LlmProviderFactory() {
    }

    public record Selection(LlmProvider provider, String model) {
    }

    /**
     * Create an LLM provider + model selection.
     *
     * @param forceLocal  if true, force Ollama
     * @param forceRemote if true, force Gardener
     * @param modelOverride optional explicit model (null means: config/provider default)
     */
    public static Selection create(boolean forceLocal, boolean forceRemote, String modelOverride)
            throws AiConfig.ConfigNotFoundException, IllegalArgumentException {

        if (forceLocal && forceRemote) {
            throw new IllegalArgumentException("Cannot use both local and remote provider");
        }

        AiConfig config = null;
        try {
            config = AiConfig.load();
        } catch (AiConfig.ConfigNotFoundException e) {
            // If user didn't explicitly override the provider, bubble it up.
            if (!forceLocal && !forceRemote) {
                throw e;
            }
        }

        boolean useOllama;
        if (forceLocal) {
            useOllama = true;
        } else if (forceRemote) {
            useOllama = false;
        } else {
            useOllama = config.isOllama();
        }

        LlmProvider provider;
        if (useOllama) {
            String host = (config != null && config.getOllamaHost() != null)
                ? config.getOllamaHost()
                : "http://127.0.0.1:11434";
            AiConfig.OllamaThinkMode thinkMode = (config != null) ? config.getEffectiveOllamaThinkMode() : null;
            provider = new OllamaLlmProvider(host, thinkMode);
        } else {
            // Gardener needs API key (config -> env)
            String key = (config != null) ? config.getApiKey() : null;
            if (key == null) {
                key = System.getenv("ANSWERING_MACHINE_APIKEY");
            }
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("API key required for Gardener AI provider. Configure api.key or set ANSWERING_MACHINE_APIKEY");
            }
            provider = new GardenerLlmProvider(key.trim());
        }

        String model = modelOverride;
        if (model == null) {
            if (forceLocal || forceRemote) {
                model = useOllama ? "qwen3:30b" : "gpt-50-nano";
            } else if (config.getModel() != null) {
                model = config.getModel();
            } else {
                model = useOllama ? "qwen3:30b" : "gpt-50-nano";
            }
        }

        return new Selection(provider, model);
    }
}