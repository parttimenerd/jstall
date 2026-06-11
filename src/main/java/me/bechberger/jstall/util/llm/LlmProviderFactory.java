package me.bechberger.jstall.util.llm;

import java.io.IOException;

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
     * @param forceLocal  if true, force local OpenAI-compatible provider
     * @param forceRemote if true, force Gardener
     * @param modelOverride optional explicit model (null means: config/provider default)
     */
    public static Selection create(boolean forceLocal, boolean forceRemote, String modelOverride)
            throws AiConfig.ConfigNotFoundException, IllegalArgumentException {
        return create(forceLocal, forceRemote, modelOverride, null);
    }

    /**
     * Same as the 3-arg overload, plus an optional {@code baseUrlOverride} for the local provider.
     * When non-null, that URL is used directly and llama-server auto-launch is skipped — the
     * caller is responsible for ensuring something is reachable at that URL. Useful for the
     * eval harness, which runs two llama-server instances on different ports.
     */
    public static Selection create(boolean forceLocal, boolean forceRemote, String modelOverride, String baseUrlOverride)
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

        AiConfig.Provider selectedProvider;
        if (forceLocal) {
            selectedProvider = AiConfig.Provider.LOCAL;
        } else if (forceRemote) {
            selectedProvider = AiConfig.Provider.GARDENER;
        } else {
            selectedProvider = config.provider();
        }

        LlmProvider provider;
        switch (selectedProvider) {
            case LOCAL -> {
                String host = baseUrlOverride;
                if (host == null) {
                    host = (config != null && config.localHost() != null)
                        ? config.localHost()
                        : "http://127.0.0.1:8080";
                    // Auto-launch llama-server if configured and not already running
                    String hfModel = (config != null) ? config.llamaServerModel() : null;
                    try {
                        LlamaServerLauncher.ensureRunning(host, hfModel);
                    } catch (IOException e) {
                        throw new IllegalArgumentException("Failed to start llama-server: " + e.getMessage());
                    }
                }
                provider = new OpenAiLlmProvider(host);
            }
            default -> {
                // Gardener needs API key (config -> env)
                String key = (config != null) ? config.apiKey() : null;
                if (key == null) {
                    key = System.getenv("ANSWERING_MACHINE_APIKEY");
                }
                if (key == null || key.trim().isEmpty()) {
                    throw new IllegalArgumentException("API key required for Gardener AI provider. Configure api.key or set ANSWERING_MACHINE_APIKEY");
                }
                provider = new GardenerLlmProvider(key.trim());
            }
        }

        String model = modelOverride;
        if (model == null) {
            if (forceLocal || forceRemote) {
                model = selectedProvider == AiConfig.Provider.LOCAL ? "local" : "gpt-50-nano";
            } else if (config.model() != null) {
                model = config.model();
            } else {
                model = selectedProvider == AiConfig.Provider.LOCAL ? "local" : "gpt-50-nano";
            }
        }

        return new Selection(provider, model);
    }
}
