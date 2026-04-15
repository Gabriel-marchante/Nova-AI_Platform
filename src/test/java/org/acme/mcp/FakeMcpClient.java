package org.acme.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceTemplate;
import dev.langchain4j.mcp.client.McpRoot;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

class FakeMcpClient implements McpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String key;
    private final List<ToolSpecification> tools;
    private final Map<String, Function<Map<String, Object>, ToolExecutionResult>> handlers;

    FakeMcpClient(String key, List<ToolSpecification> tools, Map<String, Function<Map<String, Object>, ToolExecutionResult>> handlers) {
        this.key = key;
        this.tools = tools;
        this.handlers = handlers;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public List<ToolSpecification> listTools() {
        return tools;
    }

    @Override
    public List<ToolSpecification> listTools(InvocationContext invocationContext) {
        return tools;
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        return execute(request);
    }

    @Override
    public ToolExecutionResult executeTool(ToolExecutionRequest request, InvocationContext invocationContext) {
        return execute(request);
    }

    private ToolExecutionResult execute(ToolExecutionRequest request) {
        Function<Map<String, Object>, ToolExecutionResult> handler = handlers.get(request.name());
        if (handler == null) {
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText("Tool no encontrada: " + request.name())
                    .build();
        }
        return handler.apply(parseArguments(request.arguments()));
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(argumentsJson, Map.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<McpResource> listResources() {
        return List.of();
    }

    @Override
    public List<McpResource> listResources(InvocationContext invocationContext) {
        return List.of();
    }

    @Override
    public List<McpResourceTemplate> listResourceTemplates() {
        return List.of();
    }

    @Override
    public List<McpResourceTemplate> listResourceTemplates(InvocationContext invocationContext) {
        return List.of();
    }

    @Override
    public McpReadResourceResult readResource(String s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public McpReadResourceResult readResource(String s, InvocationContext invocationContext) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<McpPrompt> listPrompts() {
        return List.of();
    }

    @Override
    public McpGetPromptResult getPrompt(String s, Map<String, Object> map) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkHealth() {
    }

    @Override
    public void setRoots(List<McpRoot> list) {
    }

    @Override
    public void close() {
    }
}
