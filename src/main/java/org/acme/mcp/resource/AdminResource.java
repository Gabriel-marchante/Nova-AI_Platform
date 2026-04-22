package org.acme.mcp.resource;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.mcp.model.McpServerEntity;
import org.acme.mcp.model.SystemError;
import org.acme.mcp.model.User;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class AdminResource {

    @Inject
    JsonWebToken jwt;

    private User getAdminUser() {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = User.findById(userId);
        if (user != null && "ADMIN".equals(user.role)) {
            return user;
        }
        return null;
    }

    @GET
    @Path("/errors")
    public Response getErrors() {
        if (getAdminUser() == null) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
        }
        
        // Fetch all errors sorted by date
        List<SystemError> errors = SystemError.find("order by errorDate desc").list();
        var result = errors.stream().map(e -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", e.id.toString());
            map.put("errorMessage", e.errorMessage);
            map.put("date", e.errorDate != null ? e.errorDate.toString() : "");
            map.put("count", e.occurrenceCount);
            map.put("user", e.user != null ? e.user.email : "Desconocido");
            return map;
        }).collect(Collectors.toList());

        return Response.ok(result).build();
    }

    @GET
    @Path("/users")
    public Response getUsers(@QueryParam("search") String search) {
        if (getAdminUser() == null) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
        }

        List<User> users;
        if (search != null && !search.isBlank()) {
            users = User.find("username like ?1 or email like ?1", "%" + search + "%").list();
        } else {
            users = User.listAll();
        }

        var result = users.stream().map(u -> {
            long chatCount = org.acme.mcp.model.ChatSessionEntity.count("user", u);
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", u.id.toString());
            map.put("username", u.username);
            map.put("email", u.email != null ? u.email : "");
            map.put("role", u.role);
            map.put("profilePicture", u.profilePicture != null ? u.profilePicture : "");
            map.put("chatCount", chatCount);
            map.put("createdAt", u.createdAt != null ? u.createdAt.toString() : "");
            return map;
        }).collect(Collectors.toList());

        return Response.ok(result).build();
    }

    @GET
    @Path("/users/{id}")
    public Response getUserDetails(@PathParam("id") UUID userId) {
        if (getAdminUser() == null) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
        }

        User user = User.findById(userId);
        if (user == null) return Response.status(404).build();

        long chatCount = org.acme.mcp.model.ChatSessionEntity.count("user", user);
        long projectCount = org.acme.mcp.model.ProjectEntity.count("user", user);

        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("id", user.id.toString());
        data.put("username", user.username);
        data.put("email", user.email != null ? user.email : "");
        data.put("role", user.role);
        data.put("profilePicture", user.profilePicture != null ? user.profilePicture : "");
        data.put("chatCount", chatCount);
        data.put("projectCount", projectCount);
        data.put("createdAt", user.createdAt != null ? user.createdAt.toString() : "");

        return Response.ok(data).build();
    }

    @GET
    @Path("/mcps")
    public Response getMcps() {
        if (getAdminUser() == null) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
        }
        
        List<McpServerEntity> mcps = McpServerEntity.listAll();
        var result = mcps.stream().map(m -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", m.id.toString());
            map.put("name", m.name);
            map.put("url", m.url);
            map.put("isActive", m.isActive);
            return map;
        }).collect(Collectors.toList());

        return Response.ok(result).build();
    }

    @POST
    @Path("/mcps")
    @Transactional
    public Response addMcp(Map<String, Object> body) {
        User admin = getAdminUser();
        if (admin == null) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
        }

        String name = (String) body.get("name");
        String url = (String) body.get("url");

        if (name == null || url == null) {
            return Response.status(400).entity(Map.of("error", "Nombre y URL son requeridos")).build();
        }

        if (McpServerEntity.find("url", url).firstResult() != null) {
            return Response.status(400).entity(Map.of("error", "La URL ya está registrada")).build();
        }

        McpServerEntity mcp = new McpServerEntity();
        mcp.name = name;
        mcp.url = url;
        mcp.isActive = true;
        mcp.registeredBy = admin;
        mcp.persist();

        return Response.ok(Map.of("message", "MCP añadido correctamente")).build();
    }

    @PUT
    @Path("/mcps/{id}/toggle")
    @Transactional
    public Response toggleMcp(@PathParam("id") UUID id) {
        if (getAdminUser() == null) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
        }

        McpServerEntity mcp = McpServerEntity.findById(id);
        if (mcp == null) {
            return Response.status(404).entity(Map.of("error", "MCP no encontrado")).build();
        }

        mcp.isActive = !mcp.isActive;
        mcp.persist();

        return Response.ok(Map.of("message", "Estado del MCP actualizado", "isActive", mcp.isActive)).build();
    }

    @DELETE
    @Path("/mcps/{id}")
    @Transactional
    public Response deleteMcp(@PathParam("id") UUID id) {
        if (getAdminUser() == null) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
        }

        McpServerEntity mcp = McpServerEntity.findById(id);
        if (mcp == null) {
            return Response.status(404).entity(Map.of("error", "MCP no encontrado")).build();
        }

        mcp.delete();
        return Response.ok(Map.of("message", "MCP eliminado")).build();
    }
}
