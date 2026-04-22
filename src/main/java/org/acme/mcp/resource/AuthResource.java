package org.acme.mcp.resource;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.acme.mcp.dto.AuthResponse;
import org.acme.mcp.dto.LoginRequest;
import org.acme.mcp.dto.RegisterRequest;
import org.acme.mcp.dto.UpdateKeyRequest;
import org.acme.mcp.model.User;
import org.acme.mcp.service.EncryptionService;
import org.acme.mcp.service.JwtService;
import org.acme.mcp.service.UserModelCache;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Map;
import java.util.UUID;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject
    EncryptionService encryptionService;

    @Inject
    JwtService jwtService;

    @Inject
    UserModelCache cache;

    @Inject
    JsonWebToken jwt;

    @POST
    @Path("/register")
    @Transactional
    public Response register(RegisterRequest req) {
        if (req.username == null || req.email == null || req.password == null || req.passwordConfirm == null) {
            return Response.status(400).entity(AuthResponse.error("Datos incompletos.")).build();
        }

        if (!req.password.equals(req.passwordConfirm)) {
            return Response.status(400).entity(AuthResponse.error("Las contraseñas no coinciden.")).build();
        }

        if (User.findByUsername(req.username) != null) {
            return Response.status(400).entity(AuthResponse.error("El nombre de usuario ya está en uso.")).build();
        }

        if (User.find("email", req.email).firstResult() != null) {
            return Response.status(400).entity(AuthResponse.error("El correo ya está en uso.")).build();
        }

        User user = new User();
        user.username = req.username;
        user.email = req.email.toLowerCase();
        user.passwordHash = BCrypt.hashpw(req.password, BCrypt.gensalt());
        if (req.geminiKey != null && !req.geminiKey.isBlank()) {
            user.geminiKeyEnc = encryptionService.encrypt(req.geminiKey);
        }
        
        // Asignar rol admin si el correo coincide
        if ("gabrielmarchantebanuls1975@gmail.com".equalsIgnoreCase(user.email)) {
            user.role = "ADMIN";
        }
        
        user.persist();

        String token = jwtService.generateToken(user);
        return Response.ok(AuthResponse.success(token)).build();
    }

    @POST
    @Path("/login")
    @Transactional
    public Response login(LoginRequest req) {
        if (req.username == null || req.password == null) {
            return Response.status(400).entity(AuthResponse.error("Datos incompletos.")).build();
        }

        User user = User.findByUsername(req.username);
        if (user == null || !BCrypt.checkpw(req.password, user.passwordHash)) {
            return Response.status(401).entity(AuthResponse.error("Credenciales invalidas.")).build();
        }

        String token = jwtService.generateToken(user);
        return Response.ok(AuthResponse.success(token)).build();
    }

    @PUT
    @Path("/profile")
    @Authenticated
    @Transactional
    public Response updateProfile(UpdateKeyRequest req) {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = User.findById(userId);
        if (user == null) {
            return Response.status(404).entity(Map.of("error", "Usuario no encontrado")).build();
        }

        if (req.newUsername != null && !req.newUsername.isBlank() && !req.newUsername.equals(user.username)) {
            if (User.findByUsername(req.newUsername) != null) {
                return Response.status(400).entity(Map.of("error", "Nombre de usuario ya en uso")).build();
            }
            user.username = req.newUsername.trim();
        }

        if (req.newPassword != null && !req.newPassword.isBlank()) {
            user.passwordHash = BCrypt.hashpw(req.newPassword, BCrypt.gensalt());
        }

        if (req.newProfilePicture != null) {
            user.profilePicture = req.newProfilePicture;
        }

        if (req.newGeminiKey != null && !req.newGeminiKey.isBlank()) {
            user.geminiKeyEnc = encryptionService.encrypt(req.newGeminiKey);
        }
        if (req.newOpenAiKey != null && !req.newOpenAiKey.isBlank()) {
            user.openaiKeyEnc = encryptionService.encrypt(req.newOpenAiKey);
        }
        if (req.newClaudeKey != null && !req.newClaudeKey.isBlank()) {
            user.anthropicKeyEnc = encryptionService.encrypt(req.newClaudeKey);
        }

        user.persist();
        cache.invalidate(userId); // Invalidate models cache

        String token = jwtService.generateToken(user);
        return Response.ok(Map.of("message", "Perfil actualizado correctamente", "token", token)).build();
    }
}
