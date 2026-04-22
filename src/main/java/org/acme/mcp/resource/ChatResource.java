package org.acme.mcp.resource;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Sort;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.mcp.model.ChatMessageEntity;
import org.acme.mcp.model.ChatSessionEntity;
import org.acme.mcp.model.User;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ChatResource {

    @Inject
    JsonWebToken jwt;

    @GET
    @Path("/sessions")
    public Response getSessions() {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = User.findById(userId);
        if (user == null) {
            return Response.status(404).build();
        }

        // Devolvemos solo los chats que NO tienen proyecto (historial general)
        List<ChatSessionEntity> sessions = ChatSessionEntity.find("user = ?1 and project is null", Sort.by("updatedAt").descending(), user).list();
        var result = sessions.stream().map(s -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", s.id.toString());
            map.put("title", s.title);
            map.put("updatedAt", s.updatedAt.toString());
            map.put("projectId", s.project != null ? s.project.id.toString() : null);
            return map;
        }).collect(Collectors.toList());

        return Response.ok(result).build();
    }

    @DELETE
    @Path("/sessions/{id}")
    @Transactional
    public Response deleteSession(@PathParam("id") UUID sessionId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        ChatSessionEntity session = ChatSessionEntity.findById(sessionId);
        if (session == null || !session.user.id.equals(userId)) {
            return Response.status(404).build();
        }
        
        // Borramos mensajes asociados primero (cascada manual si no está en JPA)
        ChatMessageEntity.delete("session", session);
        session.delete();
        
        return Response.noContent().build();
    }

    @PUT
    @Path("/sessions/{id}/move")
    @Transactional
    public Response moveSession(@PathParam("id") UUID sessionId, Map<String, Object> body) {
        UUID userId = UUID.fromString(jwt.getSubject());
        ChatSessionEntity session = ChatSessionEntity.findById(sessionId);
        if (session == null || !session.user.id.equals(userId)) {
            return Response.status(404).build();
        }

        Object pIdObj = body.get("projectId");
        if (pIdObj == null) {
            session.project = null;
        } else {
            UUID projectId = UUID.fromString(pIdObj.toString());
            org.acme.mcp.model.ProjectEntity project = org.acme.mcp.model.ProjectEntity.findById(projectId);
            if (project == null || !project.user.id.equals(userId)) {
                return Response.status(404).entity(Map.of("error", "Proyecto no encontrado")).build();
            }
            session.project = project;
        }
        
        session.persist();
        return Response.ok(Map.of("message", "Chat movido correctamente")).build();
    }

    @GET
    @Path("/sessions/{id}/messages")
    public Response getMessages(@PathParam("id") UUID sessionId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        ChatSessionEntity session = ChatSessionEntity.findById(sessionId);
        if (session == null || !session.user.id.equals(userId)) {
            return Response.status(404).entity(Map.of("error", "Sesión no encontrada")).build();
        }

        List<ChatMessageEntity> messages = ChatMessageEntity.find("session", Sort.by("createdAt").ascending(), session).list();
        var result = messages.stream().map(m -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", m.id.toString());
            map.put("role", m.role);
            map.put("content", m.content != null ? m.content : "");
            map.put("toolCalls", m.toolCalls != null ? m.toolCalls : "");
            return map;
        }).collect(Collectors.toList());

        return Response.ok(result).build();
    }

    @GET
    @Path("/sessions/search")
    public Response searchSessions(@QueryParam("q") String query) {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = User.findById(userId);
        if (user == null || query == null || query.isBlank()) {
            return Response.ok(List.of()).build();
        }

        String q = "%" + query.toLowerCase() + "%";
        
        // Buscar sesiones por título
        List<ChatSessionEntity> byTitle = ChatSessionEntity.find("user = ?1 and lower(title) like ?2", user, q).list();
        
        // Buscar sesiones por contenido de mensajes
        List<ChatMessageEntity> messages = ChatMessageEntity.find("session.user = ?1 and lower(content) like ?2", user, q).list();
        java.util.Set<ChatSessionEntity> sessions = new java.util.HashSet<>(byTitle);
        messages.forEach(m -> sessions.add(m.session));

        var result = sessions.stream().map(s -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", s.id.toString());
            map.put("title", s.title);
            map.put("updatedAt", s.updatedAt.toString());
            return map;
        }).collect(Collectors.toList());

        return Response.ok(result).build();
    }
}
