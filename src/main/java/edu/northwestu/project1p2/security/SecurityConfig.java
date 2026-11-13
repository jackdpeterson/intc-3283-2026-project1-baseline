package edu.northwestu.project1p2.security;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Browser security: form login against the {@code users} table, a server-side
 * session, and CSRF protection on every state-changing request. An account must
 * confirm its email address before it can sign in.
 *
 * <p>Thymeleaf's {@code th:action} injects the CSRF hidden field automatically,
 * so every POST form in the templates (register, login, logout, confirm) carries it.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The homepage: public landing page when signed out, dashboard when signed in. */
    public static final String HOME = "/";

    /** The sign-in page; also the URL Spring Security's login filter processes on POST. */
    public static final String LOGIN = "/login";

    /**
     * Sign-out. {@code POST} is processed by Spring Security's logout filter (CSRF
     * protected); {@code GET} renders the signed-out page (see {@code LogoutController}).
     */
    public static final String LOGOUT = "/logout";

    /** Paths that must work before sign-in: front door, sign-in/out, registration, email confirmation, assets. */
    private static final String[] PUBLIC_PATHS = {
        HOME, LOGIN, LOGOUT, "/register", "/error",
        "/check-email", "/verify-email/**",
        // Google sign-in: start (/oauth2/authorization/*) and the provider's redirect back.
        "/oauth2/**", "/login/oauth2/**",
        "/css/**", "/js/**", "/images/**", "/favicon.ico",
    };

    @Bean
    public SecurityFilterChain browserSecurityFilterChain(
            final HttpSecurity http,
            final OAuthLoginSuccessHandler oauthSuccessHandler,
            final ObjectProvider<RequestMappingHandlerMapping> handlerMapping) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // No route at this path: let it through so it answers 404, even
                        // when signed out, instead of redirecting to the login page.
                        .requestMatchers(new UnmappedRequestMatcher(handlerMapping)).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage(LOGIN)
                        // The login form posts "email" rather than Spring's default "username".
                        .usernameParameter("email")
                        // Return to the originally requested page, or the dashboard.
                        .defaultSuccessUrl(HOME)
                        .failureHandler(loginFailureHandler()))
                // "Sign in with Google": the provider authenticates, then the success handler
                // links it to a local user (see OAuthLoginSuccessHandler).
                .oauth2Login(oauth -> oauth
                        .loginPage(LOGIN)
                        .successHandler(oauthSuccessHandler)
                        .failureUrl(LOGIN + "?oauth"))
                // With CSRF enabled the logout filter only matches POST /logout, so the
                // redirect target GET /logout reaches the controller.
                .logout(logout -> logout
                        .logoutUrl(LOGOUT)
                        .logoutSuccessUrl(LOGOUT));
        return http.build();
    }

    /**
     * Username/password provider with the email-confirmation check added after the
     * password check (see {@link VerifiedEmailChecker}).
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider(final UserDetailsService userDetailsService,
                                                            final PasswordEncoder passwordEncoder) {
        final DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setPostAuthenticationChecks(new VerifiedEmailChecker());
        return provider;
    }

    /**
     * Delegating encoder: stores hashes as {@code {bcrypt}$2a$...} so the algorithm
     * can be upgraded later without invalidating existing passwords.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Unconfirmed account (correct password) → resend prompt; anything else → generic error. */
    private static AuthenticationFailureHandler loginFailureHandler() {
        final ExceptionMappingAuthenticationFailureHandler handler = new ExceptionMappingAuthenticationFailureHandler();
        handler.setDefaultFailureUrl(LOGIN + "?error");
        handler.setExceptionMappings(Map.of(DisabledException.class.getName(), LOGIN + "?unverified"));
        return handler;
    }
}
