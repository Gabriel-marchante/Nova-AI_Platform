package org.acme.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpStructuredAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void agentUsesToolsFromMultipleMcpServers() throws Exception {
        McpSchemaMapper schemaMapper = new McpSchemaMapper();
        McpAgentService agentService = new McpAgentService();
        setField(agentService, "mapper", MAPPER);
        setField(agentService, "schemaMapper", schemaMapper);

        ToolSpecification weatherTool = ToolSpecification.builder()
                .name("getWeather")
                .description("Weather tool")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("city")
                        .required("city")
                        .build())
                .build();
        ToolSpecification clockTool = ToolSpecification.builder()
                .name("getClock")
                .description("Clock tool")
                .parameters(JsonObjectSchema.builder().build())
                .build();

        FakeMcpClient weatherClient = new FakeMcpClient(
                "http://weather.test/mcp/v1",
                List.of(weatherTool),
                Map.of("getWeather", arguments -> ToolExecutionResult.builder()
                        .result(Map.of("content", List.of(Map.of("type", "text", "text", "Clima simulado para " + arguments.get("city")))))
                        .resultText("Clima simulado para " + arguments.get("city"))
                        .build())
        );
        FakeMcpClient clockClient = new FakeMcpClient(
                "http://clock.test/mcp/v1",
                List.of(clockTool),
                Map.of("getClock", arguments -> ToolExecutionResult.builder()
                        .result(Map.of("content", List.of(Map.of("type", "text", "text", "Hora simulada 13:00"))))
                        .resultText("Hora simulada 13:00")
                        .build())
        );

        List<McpSession> sessions = List.of(
                new McpSession(weatherClient.key(), weatherClient, List.of(weatherTool)),
                new McpSession(clockClient.key(), clockClient, List.of(clockTool))
        );

        AtomicInteger callCount = new AtomicInteger();
        AtomicReference<List<ToolSpecification>> seenTools = new AtomicReference<>();

        ChatModel fakeModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                if (callCount.getAndIncrement() == 0) {
                    seenTools.set(request.toolSpecifications());
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(List.of(
                                    ToolExecutionRequest.builder()
                                            .id("tool-1")
                                            .name("getWeather")
                                            .arguments("{\"city\":\"Gandia\"}")
                                            .build(),
                                    ToolExecutionRequest.builder()
                                            .id("tool-2")
                                            .name("getClock")
                                            .arguments("{}")
                                            .build()
                            )))
                            .build();
                }

                List<ToolExecutionResultMessage> toolResults = request.messages().stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .toList();

                assertEquals(2, toolResults.size());
                assertTrue(toolResults.stream().anyMatch(message -> message.toolName().equals("getWeather")
                        && message.text().contains("Clima simulado para Gandia")));
                assertTrue(toolResults.stream().anyMatch(message -> message.toolName().equals("getClock")
                        && message.text().contains("Hora simulada 13:00")));

                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("Clima simulado para Gandia. Hora simulada 13:00."))
                        .build();
            }
        };

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) agentService.runAssistant(
                fakeModel,
                java.util.UUID.randomUUID(),
                "dime el tiempo en gandia y la hora actual",
                sessions
        );

        assertEquals("Clima simulado para Gandia. Hora simulada 13:00.", result.get("reply"));
        List<Map<String, Object>> toolInfo = (List<Map<String, Object>>) result.get("toolInfo");
        assertEquals(2, toolInfo.size());
        assertEquals("getWeather", toolInfo.get(0).get("name"));
        assertEquals("getClock", toolInfo.get(1).get("name"));
        assertEquals(2, seenTools.get().size());
        assertTrue(seenTools.get().stream().anyMatch(tool -> tool.name().equals("getWeather")));
        assertTrue(seenTools.get().stream().anyMatch(tool -> tool.name().equals("getClock")));
    }

    @Test
    void duplicateToolNamesAreNamespacedPerServer() {
        McpSchemaMapper schemaMapper = new McpSchemaMapper();

        ToolSpecification lookupTool = ToolSpecification.builder()
                .name("lookup")
                .description("Shared lookup")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query")
                        .build())
                .build();

        FakeMcpClient weatherClient = new FakeMcpClient("http://weather.test:8080/mcp/v1", List.of(lookupTool), Map.of());
        FakeMcpClient clockClient = new FakeMcpClient("http://clock.test:9090/mcp/v1", List.of(lookupTool), Map.of());

        List<McpSession> sessions = List.of(
                new McpSession(weatherClient.key(), weatherClient, List.of(lookupTool)),
                new McpSession(clockClient.key(), clockClient, List.of(lookupTool))
        );

        Map<String, Integer> occurrences = schemaMapper.collectToolOccurrences(sessions);
        assertEquals(2, occurrences.get("lookup"));

        List<Map<String, Object>> weatherTools = schemaMapper.toApiTools(sessions.get(0), occurrences);
        List<Map<String, Object>> clockTools = schemaMapper.toApiTools(sessions.get(1), occurrences);

        String weatherName = (String) weatherTools.get(0).get("name");
        String clockName = (String) clockTools.get(0).get("name");

        assertTrue(weatherName.endsWith("__lookup"));
        assertTrue(clockName.endsWith("__lookup"));
        assertFalse(weatherName.equals(clockName));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
