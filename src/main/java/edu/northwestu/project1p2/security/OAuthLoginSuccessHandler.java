package edu.northwestu.project1p2.security;

import edu.northwestu.project1p2.services.OAuthLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Runs after the provider (Google) has authenticated the person. Links the provider
 * identity to a local user, then replaces the OAuth2 authentication with the same
 * {@link AppUserDetails} principal that form login produces, so the rest of the
 * application sees one kind of signed-in user.
 */
@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** Where to send the browser when the identity cannot be linked to an account. */
    static final String FAILURE_URL = SecurityConfig.LOGIN + "?oauth";

    private final OAuthLoginService loginService;
    private final SecurityContextHolderStrategy contextStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final AuthenticationSuccessHandler redirect = new SavedRequestAwareAuthenticationSuccessHandler();

    public OAuthLoginSuccessHandler(final OAuthLoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void onAuthenticationSuccess(final HttpServletRequest request, final HttpServletResponse response,
                                        final Authentication authentication) throws IOException {
        final Optional<AppUserDetails> principal = this.resolve(authentication);
        if (principal.isEmpty()) {
            this.contextStrategy.clearContext();
            request.getSession().invalidate();
            response.sendRedirect(request.getContextPath() + FAILURE_URL);
            return;
        }
        final UsernamePasswordAuthenticationToken local = UsernamePasswordAuthenticationToken.authenticated(
                principal.get(), null, principal.get().getAuthorities());
        final SecurityContext context = this.contextStrategy.createEmptyContext();
        context.setAuthentication(local);
        this.contextStrategy.setContext(context);
        this.contextRepository.saveContext(context, request, response);
        try {
            this.redirect.onAuthenticationSuccess(request, response, local);
        } catch (final jakarta.servlet.ServletException e) {
            throw new IOException(e);
        }
    }

    private Optional<AppUserDetails> resolve(final Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof OidcUser oidc
                && oidc.getSubject() != null) {
            final String email = oidc.getEmail() == null ? "" : oidc.getEmail();
            final String name = oidc.getFullName() == null ? "" : oidc.getFullName();
            return this.loginService
                    .signIn(token.getAuthorizedClientRegistrationId(), oidc.getSubject(), email,
                            Boolean.TRUE.equals(oidc.getEmailVerified()), name)
                    .map(AppUserDetails::from);
        }
        return Optional.empty();
    }
}
