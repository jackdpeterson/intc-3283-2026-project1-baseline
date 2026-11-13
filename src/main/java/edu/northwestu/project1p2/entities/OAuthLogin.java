package edu.northwestu.project1p2.entities;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A row in {@code oauth_logins}: links a local {@code users} row to that person's
 * stable id at an identity provider (for Google, the OIDC {@code sub} claim).
 */
@Table(name = "oauth_logins")
public class OAuthLogin {

    @Id
    private @Nullable Long id;

    private Long userId;

    private Long clientId;

    private String providerUserId;

    private LocalDateTime createdAt;

    private LocalDateTime lastLoginAt;

    public OAuthLogin(final Long userId, final Long clientId, final String providerUserId,
                      final LocalDateTime createdAt, final LocalDateTime lastLoginAt) {
        this.userId = userId;
        this.clientId = clientId;
        this.providerUserId = providerUserId;
        this.createdAt = createdAt;
        this.lastLoginAt = lastLoginAt;
    }

    public void recordLogin(final LocalDateTime now) {
        this.lastLoginAt = now;
    }

    public @Nullable Long getId() {
        return this.id;
    }

    public Long getUserId() {
        return this.userId;
    }

    public Long getClientId() {
        return this.clientId;
    }

    public String getProviderUserId() {
        return this.providerUserId;
    }

    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }

    public LocalDateTime getLastLoginAt() {
        return this.lastLoginAt;
    }
}
