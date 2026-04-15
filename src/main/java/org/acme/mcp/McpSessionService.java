package org.acme.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@ApplicationScoped
public class McpSessionService {

    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2024-11-05");
    private static final List<String> MCP_PATHS = List.of("", "/mcp", "/mcp/v1", "/sse", "/mcp/sse", "/mcp/v1/sse");

    @Inject
    ObjectMapper mapper;

    @Inject
    McpSchemaMapper schemaMapper;

    private final ConcurrentMap<String, McpSession> sessions = new ConcurrentHashMap<>();

    public McpSession getOrOpenSession(String serverUrl) throws Exception {
        McpSession existing = sessions.get(serverUrl);
        if (existing != null) {
            return existing;
        }

        McpSession session = openSession(serverUrl);
        sessions.put(serverUrl, session);
        return session;
    }

    public McpSession getSession(String serverUrl) {
        return sessions.get(serverUrl);
    }

    public List<McpSession> connectedSessions() {
        return new ArrayList<>(sessions.values());
    }

    public void removeSession(String serverUrl) {
        McpSession removed = sessions.remove(serverUrl);
        if (removed != null) {
            removed.closeQuietly();
        }
    }

    public ToolExecutionResult executeTool(McpSession session, String requestedToolName, Map<String, Object> arguments) throws Exception {
        String toolName = schemaMapper.resolveExecutionToolName(session, requestedToolName, schemaMapper.collectToolOccurrences(sessions.values()));
        return session.client().executeTool(ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(toolName)
                .arguments(mapper.writeValueAsString(arguments))
                .build());
    }

    McpSession openSession(String rawUrl) throws Exception {
        List<String> errors = new ArrayList<>();
        for (String endpoint : candidateEndpoints(rawUrl)) {
            for (String protocolVersion : PROTOCOL_VERSIONS) {
                try {
                    McpClient client = buildStreamableClient(endpoint, protocolVersion);
                    List<ToolSpecification> tools = client.listTools();
                    return new McpSession(endpoint, client, tools);
                } catch (Exception e) {
                    errors.add("streamable " + endpoint + " @ " + protocolVersion + " -> " + e.getMessage());
                }

                try {
                    McpClient client = buildSseClient(endpoint, protocolVersion);
                    List<ToolSpecification> tools = client.listTools();
                    return new McpSession(endpoint, client, tools);
                } catch (Exception e) {
                    errors.add("sse " + endpoint + " @ " + protocolVersion + " -> " + e.getMessage());
                }
            }
        }
        throw new IllegalStateException(String.join(" | ", errors));
    }


    private McpClient buildStreamableClient(String endpointUrl, String protocolVersion) {
        return DefaultMcpClient.builder()
                .key(endpointUrl)
                .clientName("QuarkusClient")
                .clientVersion("1.0.0")
                .protocolVersion(protocolVersion)
                .transport(StreamableHttpMcpTransport.builder()
                        .url(endpointUrl)
                        .timeout(Duration.ofSeconds(20))
                        .build())
                .initializationTimeout(Duration.ofSeconds(20))
                .toolExecutionTimeout(Duration.ofSeconds(20))
                .build();
    }

    private McpClient buildSseClient(String endpointUrl, String protocolVersion) {
        return DefaultMcpClient.builder()
                .key(endpointUrl)
                .clientName("QuarkusClient")
                .clientVersion("1.0.0")
                .protocolVersion(protocolVersion)
                .transport(HttpMcpTransport.builder()
                        .sseUrl(endpointUrl)
                        .timeout(Duration.ofSeconds(20))
                        .build())
                .initializationTimeout(Duration.ofSeconds(20))
                .toolExecutionTimeout(Duration.ofSeconds(20))
                .build();
    }

    private List<String> candidateEndpoints(String rawUrl) {
        java.net.URI base = java.net.URI.create(rawUrl.trim());
        List<String> endpoints = new ArrayList<>();
        for (String suffix : MCP_PATHS) {
            if (suffix.isEmpty()) {
                endpoints.add(base.toString());
                continue;
            }

            String path = base.getPath() == null ? "" : base.getPath();
            if (path.equals(suffix) || path.endsWith(suffix)) {
                endpoints.add(base.toString());
            } else if (path.isBlank() || "/".equals(path)) {
                endpoints.add(base.resolve(suffix.startsWith("/") ? suffix.substring(1) : suffix).toString());
            } else {
                String normalized = rawUrl.endsWith("/") ? rawUrl.substring(0, rawUrl.length() - 1) : rawUrl;
                endpoints.add(normalized + suffix);
            }
        }
        return endpoints.stream().distinct().toList();
    }
}
