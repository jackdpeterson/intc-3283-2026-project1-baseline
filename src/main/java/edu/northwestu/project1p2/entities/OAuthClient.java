package edu.northwestu.project1p2.entities;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A row in {@code oauth_clients}: an identity provider we accept, keyed by the
 * Spring Security client registration id (for example {@code google}).
 */
@Table(name = "oauth_clients")
public class OAuthClient {

    @Id
    private @Nullable Long id;

    private String registrationId;

    private String displayName;

    private LocalDateTime createdAt;

    public OAuthClient(final String registrationId, final String displayName, final LocalDateTime createdAt) {
        this.registrationId = registrationId;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    public @Nullable Long getId() {
        return this.id;
    }

    public String getRegistrationId() {
        return this.registrationId;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }
}
