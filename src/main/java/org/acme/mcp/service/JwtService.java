package org.acme.mcp.service;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import org.acme.mcp.model.User;

@ApplicationScoped
public class JwtService {

    public String generateToken(User user) {
        return Jwt.subject(user.id.toString())
                .issuer("https://nova-agent.com/issuer")
                .claim("username", user.username)
                .claim("email", user.email != null ? user.email : "")
                .claim("role", user.role != null ? user.role : "USER")
                .claim("profilePicture", user.profilePicture != null ? user.profilePicture : "")
                .sign();
    }
}
