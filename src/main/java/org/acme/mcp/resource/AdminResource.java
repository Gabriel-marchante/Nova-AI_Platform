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
        
        // Fetch all errors
        List<SystemError> errors = SystemError.listAll();
        var result = errors.stream().map(e -> Map.of(
                "id", e.id,
                "errorMessage", e.errorMessage,
                "date", e.errorDate.toString(),
                "count", e.occurrenceCount,
                "user", e.user != null ? e.user.email : "Unknown"
        )).collect(Collectors.toList());

        return Response.ok(result).build();
    }

    @GET
    @Path("/mcps")
    public Response getMcps() {
        if (getAdminUser() == null) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Access denied")).build();
        }
        
        List<McpServerEntity> mcps = McpServerEntity.listAll();
        var result = mcps.stream().map(m -> Map.of(
                "id", m.id,
                "name", m.name,
                "url", m.url,
                "isActive", m.isActive
        )).collect(Collectors.toList());

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
