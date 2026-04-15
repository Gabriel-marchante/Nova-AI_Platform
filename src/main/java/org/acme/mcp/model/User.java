package org.acme.mcp.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @Column(unique = true, nullable = false)
    public String username;

    @Column(unique = true)
    public String email;

    @Column(name = "profile_picture", columnDefinition = "TEXT")
    public String profilePicture;

    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    @Column(name = "gemini_key_enc", length = 500)
    public String geminiKeyEnc;

    @Column(name = "openai_key_enc", length = 500)
    public String openaiKeyEnc;

    @Column(name = "anthropic_key_enc", length = 500)
    public String anthropicKeyEnc;

    @Column(length = 50)
    public String role = "USER";

    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public static User findByUsername(String username) {
        return find("username", username).firstResult();
    }
}
