package edu.northwestu.project1p2.support;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/** Builds the authentication Spring Security hands the success handler after a Google login. */
public final class OidcTestUsers {

    private OidcTestUsers() {
    }

    /** Unique address per call, built with an explicit '@' char so tooling never rewrites it. */
    public static String uniqueEmail(final String local) {
        return local + "-" + UUID.randomUUID().toString().substring(0, 8) + '@' + "example.test";
    }

    public static OAuth2AuthenticationToken googleLogin(final String subject, final String email,
                                                        final boolean emailVerified, final String name) {
        final Map<String, Object> claims = new HashMap<>();
        claims.put("sub", subject);
        if (email != null) {
            claims.put("email", email);
        }
        claims.put("email_verified", emailVerified);
        if (name != null) {
            claims.put("name", name);
        }
        final OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), claims);
        final DefaultOidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }
}
