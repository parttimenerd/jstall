package me.bechberger.jstall.util.llm;

/**
 * Executes a tool call and returns the result as a string.
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * Executes the given tool call.
     *
     * @param call The tool call from the LLM
     * @return The tool result as a string (sent back to the LLM)
     */
    String execute(ToolCall call);
}
