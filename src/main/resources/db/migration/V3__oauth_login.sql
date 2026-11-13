-- Third-party sign-in. oauth_clients lists the identity providers we accept (one row per
-- Spring Security client registration, e.g. 'google'); oauth_logins links a local user to
-- that user's stable id at the provider (the OIDC "sub" claim). Match on the provider id,
-- never on the email address, which the provider's user can change.
CREATE TABLE oauth_clients
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    registration_id TEXT      NOT NULL UNIQUE,
    display_name    TEXT      NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE oauth_logins
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    client_id        BIGINT    NOT NULL REFERENCES oauth_clients (id),
    provider_user_id TEXT      NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- One provider identity maps to exactly one user, and a user has one identity per provider.
    CONSTRAINT oauth_logins_provider_identity_uq UNIQUE (client_id, provider_user_id),
    CONSTRAINT oauth_logins_user_client_uq UNIQUE (user_id, client_id)
);

CREATE INDEX oauth_logins_user_id_idx ON oauth_logins (user_id);

INSERT INTO oauth_clients (registration_id, display_name)
VALUES ('google', 'Google');
