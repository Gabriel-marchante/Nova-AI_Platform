package org.acme.mcp;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.acme.mcp.model.McpServerEntity;
import org.acme.mcp.model.SystemError;
import org.acme.mcp.model.User;
import org.acme.mcp.service.UserModelCache;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/mcp")
public class McpController {

    @Inject
    JsonWebToken jwt;

    @Inject
    UserModelCache cache;

    @Inject
    McpSessionService sessionService;

    @Inject
    McpSchemaMapper schemaMapper;

    @Inject
    McpAgentService agentService;

    @POST
    @Path("/connect")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object connectToServer(Map<String, Object> payload) {
        String serverUrl = readString(payload, "url");
        if (serverUrl == null || serverUrl.isBlank()) {
            return Map.of("error", "La URL del servidor MCP es obligatoria.");
        }

        String normalizedUrl = serverUrl.trim();
        try {
            McpSession session = sessionService.getOrOpenSession(normalizedUrl);
            return Map.of(
                    "tools", schemaMapper.toApiTools(session, schemaMapper.collectToolOccurrences(sessionService.connectedSessions())),
                    "endpoint", session.endpointUrl()
            );
        } catch (Exception e) {
            sessionService.removeSession(normalizedUrl);
            return Map.of("error", "Error de conexion MCP: " + e.getMessage());
        }
    }

    @POST
    @Path("/execute")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Object executeTool(Map<String, Object> payload) {
        if (payload == null) {
            return Map.of("error", "Payload vacio.");
        }

        String serverUrl = readString(payload, "url");
        String requestedToolName = readString(payload, "toolName");
        McpSession session = sessionService.getSession(serverUrl);
        if (session == null) {
            return Map.of("error", "No existe una sesion MCP activa para esa URL.");
        }
        if (requestedToolName == null || requestedToolName.isBlank()) {
            return Map.of("error", "El nombre de la herramienta es obligatorio.");
        }

        try {
            ToolExecutionResult result = sessionService.executeTool(session, requestedToolName, readMap(payload.get("arguments")));
            return Map.of(
                    "isError", result.isError(),
                    "result", result.result(),
                    "text", result.resultText()
            );
        } catch (Exception e) {
            return Map.of("error", "Error ejecutando la herramienta: " + e.getMessage());
        }
    }

    @POST
    @Path("/chat")
    @Blocking
    @Authenticated
    @Transactional
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public Object chatWithAgent(Map<String, Object> payload) {
        String userMessage = readString(payload, "message");
        List<Map<String, String>> attachments = (List<Map<String, String>>) payload.get("attachments");
        
        StringBuilder fullPrompt = new StringBuilder();
        if (attachments != null && !attachments.isEmpty()) {
            for (Map<String, String> att : attachments) {
                String name = att.getOrDefault("fileName", "adjunto");
                String content = att.getOrDefault("fileContent", "");
                fullPrompt.append("[Fichero adjunto: ").append(name).append("]\n")
                        .append(content).append("\n\n");
            }
        }
        fullPrompt.append(userMessage != null ? userMessage : "");

        String finalMessage = fullPrompt.toString();
        if (finalMessage.isBlank()) {
            return Map.of("error", "El mensaje es obligatorio.");
        }


        String aiModel = readString(payload, "model");
        UUID userId = UUID.fromString(jwt.getSubject());
        User currentUser = User.findById(userId);

        String sessionIdStr = readString(payload, "sessionId");
        org.acme.mcp.model.ChatSessionEntity chatSession;
        if (sessionIdStr == null || sessionIdStr.isBlank()) {
            chatSession = new org.acme.mcp.model.ChatSessionEntity();
            chatSession.user = currentUser;
            chatSession.title = userMessage.length() > 30 ? userMessage.substring(0, 30) + "..." : userMessage;
            
            String projectIdStr = readString(payload, "projectId");
            if (projectIdStr != null && !projectIdStr.isBlank()) {
                org.acme.mcp.model.ProjectEntity project = org.acme.mcp.model.ProjectEntity.findById(UUID.fromString(projectIdStr));
                if (project != null && project.user.id.equals(userId)) {
                    chatSession.project = project;
                }
            }
            chatSession.persist();
        } else {
            chatSession = org.acme.mcp.model.ChatSessionEntity.findById(UUID.fromString(sessionIdStr));
            if (chatSession == null || !chatSession.user.id.equals(userId)) {
                return Map.of("error", "Sesión de chat no encontrada.");
            }
        }

        // Intentar conectar los MCP activos dinámicamente si no están conectados
        List<McpServerEntity> allAvailable = McpServerEntity.list("isActive", true);
        for (McpServerEntity mcp : allAvailable) {
            try {
                sessionService.getOrOpenSession(mcp.url);
            } catch (Exception ignored) {
                // If it fails to connect globally, we just skip it for now to not break the chat
            }
        }

        List<McpSession> connectedSessions = sessionService.connectedSessions();

        ChatModel chatModel;
        try {
            chatModel = cache.getModel(userId, aiModel);
        } catch (RuntimeException e) {
            return Map.of("error", "Error con el modelo seleccionado: " + e.getMessage());
        }

        try {
            var result = (Map<String, Object>) agentService.runAssistant(chatModel, chatSession.id, finalMessage, connectedSessions);
            
            // Return sessionId to frontend so it can append future messages
            java.util.Map<String, Object> finalResult = new java.util.HashMap<>(result);
            finalResult.put("sessionId", chatSession.id.toString());
            return finalResult;
        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : e.toString();
            if (error.contains("429") || error.contains("RESOURCE_EXHAUSTED") || error.contains("quota")) {
                error = "Has agotado tu cuota gratuita de Gemini para hoy. Por favor, revisa tu plan en Google AI Studio o espera a que se reinicie tu cuota.";
            }

            // Guardar en tabla de errores para administrador si es error de sistema o MCP
            saveSystemError(currentUser, error);
            return Map.of("error", error);
        }
    }

    private void saveSystemError(User user, String errorMessage) {
        SystemError existing = SystemError.findByUserAndMessage(user, errorMessage);
        if (existing != null) {
            existing.occurrenceCount += 1;
            existing.persist();
        } else {
            SystemError err = new SystemError();
            err.user = user;
            err.errorMessage = errorMessage;
            err.persist();
        }
    }

    private Map<String, Object> readMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private String readString(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
