package me.bechberger.jstall.util.llm;

import java.util.List;
import java.util.Map;

/**
 * Defines a tool that the LLM can invoke during a chat conversation.
 *
 * <p>Tools follow the OpenAI function-calling convention: the model emits a
 * {@code tool_calls} array with function name + JSON arguments; the application
 * executes the tool and sends back a message with role {@code "tool"}.
 */
public record ToolDefinition(String name, String description, List<Parameter> parameters) {

    /**
     * A parameter of a tool.
     */
    public record Parameter(String name, String type, String description, boolean required) {

        public Parameter(String name, String type, String description) {
            this(name, type, description, true);
        }
    }

    /**
     * Converts this definition to the OpenAI tools JSON schema.
     */
    public Map<String, Object> toOpenAiSchema() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        List<String> requiredParams = new java.util.ArrayList<>();

        for (Parameter param : parameters) {
            Map<String, Object> prop = new java.util.LinkedHashMap<>();
            prop.put("type", param.type());
            prop.put("description", param.description());
            properties.put(param.name(), prop);
            if (param.required()) {
                requiredParams.add(param.name());
            }
        }

        Map<String, Object> paramsSchema = new java.util.LinkedHashMap<>();
        paramsSchema.put("type", "object");
        paramsSchema.put("properties", properties);
        if (!requiredParams.isEmpty()) {
            paramsSchema.put("required", requiredParams);
        }

        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", paramsSchema);

        Map<String, Object> tool = new java.util.LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }
}
