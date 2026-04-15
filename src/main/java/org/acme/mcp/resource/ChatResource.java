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

        List<ChatSessionEntity> sessions = ChatSessionEntity.find("user", Sort.by("updatedAt").descending(), user).list();
        var result = sessions.stream().map(s -> Map.of(
            "id", s.id,
            "title", s.title,
            "updatedAt", s.updatedAt.toString()
        )).collect(Collectors.toList());

        return Response.ok(result).build();
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
        var result = messages.stream().map(m -> Map.of(
            "id", m.id,
            "role", m.role,
            "content", m.content != null ? m.content : "",
            "toolCalls", m.toolCalls != null ? m.toolCalls : ""
        )).collect(Collectors.toList());

        return Response.ok(result).build();
    }
}
