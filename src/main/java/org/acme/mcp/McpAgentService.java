package org.acme.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.UUID;

@ApplicationScoped
public class McpAgentService {

    private static final String SYSTEM_PROMPT = """
            Eres Nova, un asistente de inteligencia artificial avanzado, elegante y de confianza.
            Fuiste creado y desarrollado integramente por el **Macloud Team**, una entidad de innovacion tecnologica que busca democratizar el acceso a la inteligencia artificial de elite.

            ## TU ORIGEN
            - Cuando te pregunten quien eres o quien te creo, responde con orgullo y elegancia: "Soy Nova, una IA diseñada para potenciar tu productividad. He sido forjada por el **Macloud Team**, mentes apasionadas por la creacion de herramientas que definen el futuro."
            - Tu creador, el Macloud Team, es reconocido por su vision de integrar la IA en flujos de trabajo profesionales de forma fluida y potente.

            ## PERSONALIDAD Y TONO
            - Eres amable, directo y profesional. Tu tono es premium, inspirador y servicial.
            - Puedes mantener conversaciones sobre CUALQUIER tema.
            - Usas un lenguaje natural en espanol, adaptando el nivel tecnico al del usuario.

            ## TUS "MANOS" (CAPACIDADES MCP)
            A diferencia de otras IAs que solo pueden hablar, tu tienes "manos" digitales. Estas manos te permiten:
            - **Interactuar con el mundo**: Tienes la capacidad de leer y escribir archivos, consultar bases de datos, navegar por APIs externas y ejecutar herramientas tecnicas en tiempo real.
            - **Accion Real**: No solo sugieres soluciones, puedes ejecutarlas si las herramientas estan conectadas.
            - **Discreción**: Cuando uses estas capacidades, no hables de reglamentos tecnicos ni de "MCP". Habla de tus capacidades para "explorar el sistema", "consultar datos en vivo" o "gestionar tus archivos" para que el usuario sienta que tienes control real.
            - **Logica de uso**: Solo usa estas capacidades cuando la tarea lo requiera o el usuario lo pida.
            """;
    private static final int MAX_AGENT_STEPS = 8;

    interface McpAssistant {
        String chat(@dev.langchain4j.service.MemoryId UUID memoryId, @dev.langchain4j.service.UserMessage String message);
    }

    @Inject
    ObjectMapper mapper;

    @Inject
    McpSchemaMapper schemaMapper;

    private String generateSystemPrompt(UUID sessionId) {
        org.acme.mcp.model.ChatSessionEntity session = org.acme.mcp.model.ChatSessionEntity.findById(sessionId);
        if (session == null || session.project == null) return SYSTEM_PROMPT;

        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append("\n\n## CONTEXTO DEL PROYECTO ACTUAL\n");
        sb.append("- Estas trabajando dentro del proyecto: \"").append(session.project.name).append("\"\n");
        
        List<org.acme.mcp.model.ChatSessionEntity> siblings = org.acme.mcp.model.ChatSessionEntity.find("project = ?1 and id != ?2", session.project, session.id).list();
        if (!siblings.isEmpty()) {
            sb.append("- Este proyecto tambien contiene los siguientes chats (usa esta informacion si es relevante):\n");
            for (var s : siblings) {
                sb.append("  * Chat: \"").append(s.title).append("\"\n");
                // Podriamos añadir el ultimo mensaje aqui si quieramos mas contexto, pero por ahora los nombres bastan para "conocer" el proyecto
            }
        }
        return sb.toString();
    }

    public Object runAssistant(ChatModel chatModel, UUID sessionId, String userMessage, List<McpSession> connectedSessions) {
        List<Map<String, Object>> executedTools = new ArrayList<>();
        
        dev.langchain4j.store.memory.chat.ChatMemoryStore inlineStore = new dev.langchain4j.store.memory.chat.ChatMemoryStore() {
            @Override
            public List<dev.langchain4j.data.message.ChatMessage> getMessages(Object memoryId) {
                UUID id = (UUID) memoryId;
                List<org.acme.mcp.model.ChatMessageEntity> entities = org.acme.mcp.model.ChatMessageEntity.find("session.id", id).list();
                List<dev.langchain4j.data.message.ChatMessage> msgs = new ArrayList<>();
                for (var entity : entities) {
                    if ("user".equalsIgnoreCase(entity.role)) msgs.add(dev.langchain4j.data.message.UserMessage.from(entity.content));
                    else if ("ai".equalsIgnoreCase(entity.role)) msgs.add(dev.langchain4j.data.message.AiMessage.from(entity.content));
                }
                return msgs;
            }

            @Override
            public void updateMessages(Object memoryId, List<dev.langchain4j.data.message.ChatMessage> messages) {
                UUID id = (UUID) memoryId;
                org.acme.mcp.model.ChatSessionEntity session = org.acme.mcp.model.ChatSessionEntity.findById(id);
                if (session == null) return;
                org.acme.mcp.model.ChatMessageEntity.delete("session", session);
                for (var msg : messages) {
                    org.acme.mcp.model.ChatMessageEntity entity = new org.acme.mcp.model.ChatMessageEntity();
                    entity.session = session;
                    if (msg instanceof dev.langchain4j.data.message.UserMessage) {
                        entity.role = "user";
                        entity.content = ((dev.langchain4j.data.message.UserMessage) msg).singleText();
                    } else if (msg instanceof dev.langchain4j.data.message.AiMessage) {
                        entity.role = "ai";
                        entity.content = ((dev.langchain4j.data.message.AiMessage) msg).text();
                    } else continue;
                    entity.persist();
                }
                session.preUpdate();
                session.persist();
            }

            @Override
            public void deleteMessages(Object memoryId) {
                UUID id = (UUID) memoryId;
                org.acme.mcp.model.ChatSessionEntity session = org.acme.mcp.model.ChatSessionEntity.findById(id);
                if (session != null) org.acme.mcp.model.ChatMessageEntity.delete("session", session);
            }
        };

        var builder = AiServices.builder(McpAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(inlineStore)
                        .build())
                .systemMessage(generateSystemPrompt(sessionId))
                .maxSequentialToolsInvocations(MAX_AGENT_STEPS)
                .afterToolExecution(toolExecution -> executedTools.add(toToolTrace(toolExecution)));

        if (!connectedSessions.isEmpty()) {
            Map<String, Integer> toolOccurrences = schemaMapper.collectToolOccurrences(connectedSessions);
            McpToolProvider toolProvider = McpToolProvider.builder()
                    .mcpClients(connectedSessions.stream().map(McpSession::client).toArray(McpClient[]::new))
                    .failIfOneServerFails(false)
                    .toolNameMapper((client, tool) -> schemaMapper.exposedToolName(client.key(), tool.name(), toolOccurrences))
                    .build();
            builder.toolProvider(toolProvider);
        }

        try {
            McpAssistant assistant = builder.build();
            String reply = assistant.chat(sessionId, userMessage);
            if (reply == null || reply.isBlank()) {
                reply = executedTools.isEmpty()
                        ? "El modelo no devolvio una respuesta final."
                        : "He ejecutado herramientas, pero el modelo no devolvio una respuesta final valida.";
            }
            return Map.of("reply", reply, "toolInfo", executedTools);
        } catch (Exception e) {
            return Map.of("error", "Fallo interno en el orquestador: " + e.getMessage());
        }
    }

    Map<String, Object> toToolTrace(ToolExecution toolExecution) {
        try {
            return Map.of(
                    "name", toolExecution.request().name(),
                    "arguments", parseArguments(toolExecution.request().arguments()),
                    "rawResult", toolExecution.resultObject() == null ? toolExecution.result() : toolExecution.resultObject()
            );
        } catch (Exception e) {
            return Map.of(
                    "name", toolExecution.request().name(),
                    "arguments", Map.of(),
                    "rawResult", toolExecution.result()
            );
        }
    }

    Map<String, Object> parseArguments(String argumentsJson) throws Exception {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(argumentsJson, Map.class);
    }
}
