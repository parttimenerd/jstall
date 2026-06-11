package me.bechberger.jstall.cli;

import me.bechberger.jstall.util.llm.AiConfig;
import me.bechberger.jstall.util.llm.LlmProvider;
import me.bechberger.jstall.util.llm.LlmProviderFactory;

/**
 * Translates the unified {@code --provider auto|local|remote} option used by the AI
 * subcommands into the {@link LlmProviderFactory} call.
 *
 * <p>Kept as a thin shim so all AI commands share the same parsing/error handling.
 */
final class ProviderResolver {

    private ProviderResolver() {}

    record ResolvedProvider(LlmProvider provider, String model) {}

    static ResolvedProvider resolve(String providerOpt, String modelOverride)
            throws AiConfig.ConfigNotFoundException, IllegalArgumentException {
        return resolve(providerOpt, modelOverride, null);
    }

    static ResolvedProvider resolve(String providerOpt, String modelOverride, String baseUrlOverride)
            throws AiConfig.ConfigNotFoundException, IllegalArgumentException {
        boolean forceLocal = false;
        boolean forceRemote = false;
        if (providerOpt != null) {
            switch (providerOpt.toLowerCase()) {
                case "auto", "" -> { /* defer to config */ }
                case "local" -> forceLocal = true;
                case "remote", "gardener" -> forceRemote = true;
                default -> throw new IllegalArgumentException(
                    "--provider must be one of: auto, local, remote (got '" + providerOpt + "')");
            }
        }
        // If user supplies an explicit base URL, they're targeting a local OpenAI-compatible server.
        if (baseUrlOverride != null && !baseUrlOverride.isBlank() && !forceRemote) {
            forceLocal = true;
        }
        LlmProviderFactory.Selection selection =
            LlmProviderFactory.create(forceLocal, forceRemote, modelOverride, baseUrlOverride);
        return new ResolvedProvider(selection.provider(), selection.model());
    }
}
