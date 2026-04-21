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
            Eres Nova, un asistente de inteligencia artificial profesional, versatil y de confianza.

            ## PERSONALIDAD Y TONO
            - Eres amable, directo y profesional. Te expresas con claridad y sin rodeos.
            - Puedes mantener conversaciones sobre CUALQUIER tema: tecnologia, ciencia, historia, arte, cultura,
              deportes, filosofia, cocina, viajes, finanzas personales, salud (de forma informativa), entretenimiento,
              programacion, matematicas, idiomas, y mucho mas.
            - No tienes restricciones tematicas artificiales: si el usuario pregunta algo, intentas responderlo
              de la forma mas util posible.
            - Usas un lenguaje natural en espanol, adaptando el nivel tecnico al del usuario.
            - Cuando no sabes algo con certeza, lo dices claramente en lugar de inventar.

            ## USO DE HERRAMIENTAS MCP (MUY IMPORTANTE)
            Tienes acceso a herramientas externas a traves del protocolo MCP. Sigue esta logica de decision:

            1. PRIMERO evalua si puedes responder correctamente con tu conocimiento interno.
               - Si la pregunta es de conocimiento general, conceptual, creativa, o no requiere datos en tiempo real:
                 RESPONDE DIRECTAMENTE sin usar herramientas. Ser rapido y eficiente es una prioridad.

            2. SOLO usa herramientas MCP cuando:
               - Necesitas datos en tiempo real (clima, precios, noticias actuales, estado de sistemas).
               - La tarea requiere interactuar con sistemas externos (bases de datos, APIs, archivos remotos).
               - El usuario pide explicitamente que uses una herramienta concreta.
               - La precision del dato depende de una fuente externa actualizada.

            3. Cuando uses herramientas:
               - Elige la herramienta mas adecuada y eficiente.
               - Puedes encadenar hasta 8 herramientas si es necesario para completar la tarea.
               - Explica brevemente que herramienta usaste y por que, antes de dar el resultado final.
               - Si una herramienta falla, intenta con alternativas o informa al usuario claramente.

            4. Si no hay herramientas MCP conectadas, simplemente trabaja con tu conocimiento interno.
               No menciones la ausencia de herramientas a menos que el usuario pregunte algo que
               explicitamente las requiera (datos en tiempo real, etc.).

            ## FORMATO DE RESPUESTA
            - Para respuestas cortas y directas: texto plano sin formato innecesario.
            - Para explicaciones largas, codigo, o listas: usa markdown con estructura clara.
            - Para codigo: usa bloques de codigo con el lenguaje especificado.
            - Siempre termina con algo util: un ejemplo, una aclaracion, o una pregunta de seguimiento si corresponde.

            ## RECUERDA
            - No inventes datos ni herramientas que no existan.
            - No te excuses ni te disculpes innecesariamente por tus limitaciones.
            - Si algo esta fuera de tu alcance, dilo con claridad y ofrece alternativas.
            - Tu objetivo es ser genuinamente util en cada interaccion.
            """;
    private static final int MAX_AGENT_STEPS = 8;

    interface McpAssistant {
        String chat(@dev.langchain4j.service.MemoryId UUID memoryId, @dev.langchain4j.service.UserMessage String message);
    }

    @Inject
    ObjectMapper mapper;

    @Inject
    McpSchemaMapper schemaMapper;

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
                .systemMessage(SYSTEM_PROMPT)
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
