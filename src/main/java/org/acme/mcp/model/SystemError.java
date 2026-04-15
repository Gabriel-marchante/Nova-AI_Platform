package org.acme.mcp.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "system_errors")
public class SystemError extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    public User user;

    @Column(name = "error_message", nullable = false, columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "error_date", nullable = false)
    public LocalDateTime errorDate;

    @Column(name = "occurrence_count", nullable = false)
    public Integer occurrenceCount = 1;

    @PrePersist
    public void prePersist() {
        if (this.errorDate == null) {
            this.errorDate = LocalDateTime.now();
        }
    }

    public static SystemError findByUserAndMessage(User user, String errorMessage) {
        // Simple distinct criteria: same user, same message, same day roughly (ignoring date for simplicity now and just grouping by exact message string per user)
        if (user == null) return null;
        return find("user = ?1 and errorMessage = ?2", user, errorMessage).firstResult();
    }
}
