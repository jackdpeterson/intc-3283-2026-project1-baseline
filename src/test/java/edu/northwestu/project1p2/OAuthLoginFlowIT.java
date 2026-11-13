package edu.northwestu.project1p2;

import edu.northwestu.project1p2.entities.User;
import edu.northwestu.project1p2.repositories.OAuthClientRepository;
import edu.northwestu.project1p2.repositories.OAuthLoginRepository;
import edu.northwestu.project1p2.repositories.UserRepository;
import edu.northwestu.project1p2.security.OAuthLoginSuccessHandler;
import edu.northwestu.project1p2.support.OidcTestUsers;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Google sign-in through the real filter chain, success handler, service and Postgres. */
@Tag("integration")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OAuthLoginFlowIT {

    // Test framework field injection; app code uses constructor injection only.
    @Autowired
    private MockMvc mvc;
    @Autowired
    private OAuthLoginSuccessHandler handler;
    @Autowired
    private UserRepository users;
    @Autowired
    private OAuthClientRepository clients;
    @Autowired
    private OAuthLoginRepository logins;
    @Autowired
    private PasswordEncoder encoder;

    private MockHttpServletRequest completeGoogleLogin(final String subject, final String email,
                                                       final boolean verified, final String name) throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        this.handler.onAuthenticationSuccess(request, new MockHttpServletResponse(),
                OidcTestUsers.googleLogin(subject, email, verified, name));
        return request;
    }

    @Test
    void loginPageOffersGoogleSignIn() throws Exception {
        this.mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(content().string(containsString("Sign in with Google")))
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/google\"")));
    }

    @Test
    void failedGoogleSignInShowsAnErrorBanner() throws Exception {
        this.mvc.perform(get("/login").param("oauth", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Google sign-in failed")));
    }

    @Test
    void startingGoogleSignInRedirectsToGoogleWithOurClientAndScopes() throws Exception {
        this.mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://accounts.google.com/o/oauth2/v2/auth")))
                .andExpect(header().string("Location", containsString("response_type=code")))
                .andExpect(header().string("Location", containsString("scope=openid%20profile%20email")))
                .andExpect(header().string("Location", containsString("/login/oauth2/code/google")));
    }

    @Test
    void googleCallbackWithoutAStartedLoginIsSentBackToLogin() throws Exception {
        this.mvc.perform(get("/login/oauth2/code/google").param("code", "x").param("state", "y"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?oauth"));
    }

    @Test
    void firstGoogleLoginCreatesAConfirmedUserLinkedToTheGoogleSubject() throws Exception {
        final String email = OidcTestUsers.uniqueEmail("new");
        final String subject = "g-" + UUID.randomUUID();

        final MockHttpServletRequest request = completeGoogleLogin(subject, email, true, "Gina Google");

        final User user = this.users.findByEmail(email).orElseThrow();
        assertThat(user.getUsername()).isEqualTo("Gina Google");
        assertThat(user.getVerifiedAt()).isNotNull();
        assertThat(this.logins.findByClientIdAndProviderUserId(
                this.clients.findByRegistrationId("google").orElseThrow().getId(), subject).orElseThrow().getUserId())
                .isEqualTo(user.getId());

        this.mvc.perform(get("/").session((MockHttpSession) request.getSession(false)))
                .andExpect(status().isOk())
                .andExpect(view().name("index-authenticated"))
                .andExpect(content().string(containsString("Gina Google")))
                .andExpect(content().string(containsString("Sign out")));
    }

    @Test
    void secondGoogleLoginReusesTheSameUser() throws Exception {
        final String email = OidcTestUsers.uniqueEmail("again");
        final String subject = "g-" + UUID.randomUUID();
        completeGoogleLogin(subject, email, true, "Again");
        final Long first = this.users.findByEmail(email).orElseThrow().getId();

        // Same subject, even if Google now reports a different email, maps to the same user.
        completeGoogleLogin(subject, OidcTestUsers.uniqueEmail("changed"), true, "Again");

        assertThat(this.users.findByEmail(email).orElseThrow().getId()).isEqualTo(first);
        assertThat(this.users.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void googleLoginLinksToAnExistingConfirmedAccountWithoutChangingItsPassword() throws Exception {
        final String email = OidcTestUsers.uniqueEmail("exists");
        final User existing = new User(email, "Existing", this.encoder.encode("my-password"));
        existing.setVerifiedAt(java.time.LocalDateTime.now());
        final User saved = this.users.save(existing);

        completeGoogleLogin("g-" + UUID.randomUUID(), email, true, "Someone Else");

        final User after = this.users.findById(saved.getId()).orElseThrow();
        assertThat(after.getUsername()).isEqualTo("Existing");
        assertThat(this.encoder.matches("my-password", after.getPassword())).isTrue();
    }

    @Test
    void googleLoginConfirmsAnUnconfirmedAccountAndLocksOutItsPassword() throws Exception {
        final String email = OidcTestUsers.uniqueEmail("pending");
        final User saved = this.users.save(new User(email, "Pending", this.encoder.encode("attacker-password")));

        completeGoogleLogin("g-" + UUID.randomUUID(), email, true, "Real Owner");

        final User after = this.users.findById(saved.getId()).orElseThrow();
        assertThat(after.getVerifiedAt()).isNotNull();
        assertThat(this.encoder.matches("attacker-password", after.getPassword())).isFalse();
    }

    @Test
    void unverifiedGoogleEmailCreatesNothingAndIsRedirectedToLogin() throws Exception {
        final String email = OidcTestUsers.uniqueEmail("unverified");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        this.handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                OidcTestUsers.googleLogin("g-" + UUID.randomUUID(), email, false, "Nope"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauth");
        assertThat(this.users.findByEmail(email)).isEmpty();
    }
}
