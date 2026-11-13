package edu.northwestu.project1p2;

import edu.northwestu.project1p2.entities.EmailVerificationToken;
import edu.northwestu.project1p2.entities.OAuthClient;
import edu.northwestu.project1p2.entities.OAuthLogin;
import edu.northwestu.project1p2.entities.User;
import edu.northwestu.project1p2.repositories.EmailVerificationTokenRepository;
import edu.northwestu.project1p2.repositories.OAuthClientRepository;
import edu.northwestu.project1p2.repositories.OAuthLoginRepository;
import edu.northwestu.project1p2.repositories.UserRepository;
import edu.northwestu.project1p2.support.OidcTestUsers;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Postgres: V3 tables accept writes, return rows, enforce keys, and cascade deletes. */
@Tag("integration")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OAuthPersistenceIT {

    // Test framework field injection; app code uses constructor injection only.
    @Autowired
    private UserRepository users;
    @Autowired
    private OAuthClientRepository clients;
    @Autowired
    private OAuthLoginRepository logins;
    @Autowired
    private EmailVerificationTokenRepository tokens;

    private User newUser() {
        return this.users.save(new User(OidcTestUsers.uniqueEmail("p"), "Persisted", "{noop}pw"));
    }

    private Long googleId() {
        return this.clients.findByRegistrationId("google").orElseThrow().getId();
    }

    private OAuthLogin link(final User user, final String subject) {
        final LocalDateTime now = LocalDateTime.now();
        return this.logins.save(new OAuthLogin(user.getId(), this.googleId(), subject, now, now));
    }

    @Test
    void migrationSeedsTheGoogleClient() {
        final OAuthClient google = this.clients.findByRegistrationId("google").orElseThrow();

        assertThat(google.getId()).isNotNull();
        assertThat(google.getDisplayName()).isEqualTo("Google");
        assertThat(google.getCreatedAt()).isNotNull();
        assertThat(this.clients.findByRegistrationId("nope")).isEmpty();
    }

    @Test
    void clientCanBePersistedAndFetched() {
        final String registration = "test-" + UUID.randomUUID();
        final OAuthClient saved = this.clients.save(new OAuthClient(registration, "Test IdP", LocalDateTime.now()));

        assertThat(saved.getId()).isNotNull();
        assertThat(this.clients.findById(saved.getId())).get()
                .extracting(OAuthClient::getRegistrationId).isEqualTo(registration);
        assertThatThrownBy(() -> this.clients.save(new OAuthClient(registration, "Dup", LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loginLinksAUserToTheGoogleSubjectAndCanBeFetchedByIt() {
        final User user = this.newUser();
        final String subject = UUID.randomUUID().toString();
        final OAuthLogin saved = link(user, subject);

        assertThat(saved.getId()).isNotNull();
        final OAuthLogin found = this.logins.findByClientIdAndProviderUserId(this.googleId(), subject).orElseThrow();
        assertThat(found.getUserId()).isEqualTo(user.getId());
        assertThat(found.getProviderUserId()).isEqualTo(subject);
        assertThat(found.getClientId()).isEqualTo(this.googleId());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(this.logins.findByClientIdAndProviderUserId(this.googleId(), "unknown")).isEmpty();
    }

    @Test
    void recordedLoginTimeIsUpdated() {
        final OAuthLogin login = link(this.newUser(), UUID.randomUUID().toString());
        final LocalDateTime later = LocalDateTime.now().plusHours(1).withNano(0);

        login.recordLogin(later);
        this.logins.save(login);

        assertThat(this.logins.findById(login.getId()).orElseThrow().getLastLoginAt()).isEqualTo(later);
    }

    @Test
    void oneGoogleIdentityCannotBeLinkedToTwoUsers() {
        final String subject = UUID.randomUUID().toString();
        link(this.newUser(), subject);
        final User other = this.newUser();

        assertThatThrownBy(() -> link(other, subject)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneUserCannotHaveTwoGoogleIdentities() {
        final User user = this.newUser();
        link(user, UUID.randomUUID().toString());

        assertThatThrownBy(() -> link(user, UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loginForAMissingUserIsRejected() {
        final LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> this.logins.save(
                new OAuthLogin(Long.MAX_VALUE, this.googleId(), UUID.randomUUID().toString(), now, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAUserCascadesToItsLoginsAndVerificationTokens() {
        final User user = this.newUser();
        final String subject = UUID.randomUUID().toString();
        final OAuthLogin login = link(user, subject);
        final LocalDateTime now = LocalDateTime.now();
        final EmailVerificationToken token = this.tokens.save(
                new EmailVerificationToken(user.getId(), "hash-" + UUID.randomUUID(), now, now.plusHours(1)));

        this.users.deleteById(user.getId());

        assertThat(this.users.findById(user.getId())).isEmpty();
        assertThat(this.logins.findById(login.getId())).isEmpty();
        assertThat(this.logins.findByClientIdAndProviderUserId(this.googleId(), subject)).isEmpty();
        assertThat(this.tokens.findById(token.getId())).isEmpty();
    }

    @Test
    void aClientWithLoginsCannotBeDeleted() {
        final OAuthClient client = this.clients.save(
                new OAuthClient("keep-" + UUID.randomUUID(), "Keep", LocalDateTime.now()));
        final User user = this.newUser();
        final LocalDateTime now = LocalDateTime.now();
        this.logins.save(new OAuthLogin(user.getId(), client.getId(), "s-" + UUID.randomUUID(), now, now));

        assertThatThrownBy(() -> this.clients.deleteById(client.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(this.clients.findById(client.getId())).isPresent();
    }
}
