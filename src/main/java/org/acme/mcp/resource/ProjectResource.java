package org.acme.mcp.resource;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.mcp.model.ChatSessionEntity;
import org.acme.mcp.model.ProjectEntity;
import org.acme.mcp.model.User;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/api/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ProjectResource {

    @Inject
    JsonWebToken jwt;

    private User getCurrentUser() {
        return User.findById(UUID.fromString(jwt.getSubject()));
    }

    @GET
    public List<Map<String, Object>> getProjects() {
        User user = getCurrentUser();
        List<ProjectEntity> projects = ProjectEntity.find("user", user).list();
        return projects.stream().map(p -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", p.id.toString());
            map.put("name", p.name);
            map.put("createdAt", p.createdAt.toString());
            return map;
        }).collect(Collectors.toList());
    }

    @POST
    @Transactional
    public Response createProject(Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return Response.status(400).entity(Map.of("error", "Name is required")).build();
        }

        ProjectEntity project = new ProjectEntity();
        project.name = name;
        project.user = getCurrentUser();
        project.persist();

        return Response.status(201).entity(Map.of("id", project.id, "name", project.name)).build();
    }

    @GET
    @Path("/{id}/chats")
    public List<Map<String, Object>> getProjectChats(@PathParam("id") UUID projectId) {
        User user = getCurrentUser();
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null || !project.user.id.equals(user.id)) {
            throw new WebApplicationException("Project not found", 404);
        }

        List<ChatSessionEntity> sessions = ChatSessionEntity.find("project", project).list();
        return sessions.stream().map(s -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", s.id.toString());
            map.put("title", s.title);
            map.put("updatedAt", s.updatedAt.toString());
            map.put("projectId", project.id.toString());
            return map;
        }).collect(Collectors.toList());
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteProject(@PathParam("id") UUID projectId) {
        User user = getCurrentUser();
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null || !project.user.id.equals(user.id)) {
            return Response.status(404).build();
        }

        // We move chats back to history instead of deleting them as per plan
        ChatSessionEntity.update("project = null where project = ?1", project);
        
        project.delete();
        return Response.noContent().build();
    }
}
