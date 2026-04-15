package org.acme.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

import java.util.List;

public record McpSession(String endpointUrl, McpClient client, List<ToolSpecification> tools) {

    public void closeQuietly() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }
}
