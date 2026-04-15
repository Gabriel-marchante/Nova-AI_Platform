package org.acme.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void connectReturnsToolsFromOpenedSession() throws Exception {
        ToolSpecification tool = ToolSpecification.builder()
                .name("getWeather")
                .description("Fake weather tool")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("city")
                        .required("city")
                        .build())
                .build();

        FakeMcpClient client = new FakeMcpClient(
                "http://fake.test/mcp/v1",
                List.of(tool),
                Map.of("getWeather", arguments -> ToolExecutionResult.builder()
                        .resultText("ok")
                        .build())
        );

        McpSession session = new McpSession(client.key(), client, List.of(tool));
        TestMcpSessionService sessionService = new TestMcpSessionService(session, null);
        McpSchemaMapper schemaMapper = new McpSchemaMapper();

        wireSessionService(sessionService, schemaMapper);

        McpController controller = new McpController();
        setField(controller, "sessionService", sessionService);
        setField(controller, "schemaMapper", schemaMapper);
        setField(controller, "agentService", new McpAgentService());

        Object response = controller.connectToServer(Map.of("url", "http://fake.test"));
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertEquals("http://fake.test/mcp/v1", body.get("endpoint"));
        List<Map<String, Object>> tools = assertInstanceOf(List.class, body.get("tools"));
        assertEquals(1, tools.size());
        assertEquals("getWeather", tools.get(0).get("name"));

        Map<String, Object> inputSchema = assertInstanceOf(Map.class, tools.get(0).get("inputSchema"));
        assertEquals("object", inputSchema.get("type"));
        assertTrue(((Map<?, ?>) inputSchema.get("properties")).containsKey("city"));
        assertEquals(List.of("city"), inputSchema.get("required"));
    }

    @Test
    void executeUsesStoredSessionAndReturnsResultShape() throws Exception {
        ToolSpecification tool = ToolSpecification.builder()
                .name("getWeather")
                .description("Fake weather tool")
                .parameters(JsonObjectSchema.builder().addStringProperty("city").build())
                .build();

        FakeMcpClient client = new FakeMcpClient(
                "http://fake.test/mcp/v1",
                List.of(tool),
                Map.of("getWeather", arguments -> ToolExecutionResult.builder()
                        .result(Map.of("content", List.of(Map.of("type", "text", "text", "Clima simulado para " + arguments.get("city")))))
                        .resultText("Clima simulado para " + arguments.get("city"))
                        .build())
        );

        McpSession session = new McpSession(client.key(), client, List.of(tool));
        TestMcpSessionService sessionService = new TestMcpSessionService(session, null);
        McpSchemaMapper schemaMapper = new McpSchemaMapper();

        wireSessionService(sessionService, schemaMapper);

        McpController controller = new McpController();
        setField(controller, "sessionService", sessionService);
        setField(controller, "schemaMapper", schemaMapper);
        setField(controller, "agentService", new McpAgentService());
        controller.connectToServer(Map.of("url", "http://fake.test"));

        Object response = controller.executeTool(Map.of(
                "url", "http://fake.test",
                "toolName", "getWeather",
                "arguments", Map.of("city", "Madrid")
        ));
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertEquals(false, body.get("isError"));
        assertEquals("Clima simulado para Madrid", body.get("text"));
        assertTrue(body.containsKey("result"));
    }

    @Test
    void connectReportsErrorWhenSessionOpenFails() throws Exception {
        TestMcpSessionService sessionService = new TestMcpSessionService(null, new IllegalStateException("fallo MCP"));
        McpSchemaMapper schemaMapper = new McpSchemaMapper();

        wireSessionService(sessionService, schemaMapper);

        McpController controller = new McpController();
        setField(controller, "sessionService", sessionService);
        setField(controller, "schemaMapper", schemaMapper);
        setField(controller, "agentService", new McpAgentService());

        Object response = controller.connectToServer(Map.of("url", "http://broken.test"));
        Map<String, Object> body = assertInstanceOf(Map.class, response);

        assertTrue(String.valueOf(body.get("error")).contains("fallo MCP"));
        assertFalse(body.containsKey("tools"));
    }

    private static void wireSessionService(McpSessionService sessionService, McpSchemaMapper schemaMapper) throws Exception {
        setField(sessionService, "mapper", MAPPER);
        setField(sessionService, "schemaMapper", schemaMapper);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getSuperclass() != null
                ? findField(target.getClass(), fieldName)
                : target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws Exception {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    static final class TestMcpSessionService extends McpSessionService {
        private final McpSession session;
        private final RuntimeException failure;

        TestMcpSessionService(McpSession session, RuntimeException failure) {
            this.session = session;
            this.failure = failure;
        }

        @Override
        McpSession openSession(String rawUrl) {
            if (failure != null) {
                throw failure;
            }
            return session;
        }
    }
}
