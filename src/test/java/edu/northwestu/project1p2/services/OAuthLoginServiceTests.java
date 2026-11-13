package edu.northwestu.project1p2.services;

import edu.northwestu.project1p2.entities.OAuthClient;
import edu.northwestu.project1p2.entities.OAuthLogin;
import edu.northwestu.project1p2.entities.User;
import edu.northwestu.project1p2.repositories.OAuthClientRepository;
import edu.northwestu.project1p2.repositories.OAuthLoginRepository;
import edu.northwestu.project1p2.repositories.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthLoginServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Mock
    private OAuthClientRepository clients;
    @Mock
    private OAuthLoginRepository logins;
    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder encoder;

    private OAuthLoginService service;
    private OAuthClient google;

    @BeforeEach
    void setUp() {
        this.service = new OAuthLoginService(this.clients, this.logins, this.users, this.encoder, CLOCK);
        this.google = new OAuthClient("google", "Google", NOW);
        ReflectionTestUtils.setField(this.google, "id", 7L);
    }

    private static User withId(final User user, final long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void unknownProviderIsRejected() {
        when(this.clients.findByRegistrationId("github")).thenReturn(Optional.empty());

        assertThat(this.service.signIn("github", "s", "a" + '@' + "b.test", true, "A")).isEmpty();
        verify(this.logins, never()).save(any());
    }

    @Test
    void blankSubjectIsRejected() {
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));

        assertThat(this.service.signIn("google", " ", "a" + '@' + "b.test", true, "A")).isEmpty();
    }

    @Test
    void returningIdentityRecordsTheLoginAndLoadsTheLinkedUser() {
        final User user = withId(new User("a" + '@' + "b.test", "A", "pw"), 3L);
        final OAuthLogin login = new OAuthLogin(3L, 7L, "sub", NOW.minusDays(1), NOW.minusDays(1));
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));
        when(this.logins.findByClientIdAndProviderUserId(7L, "sub")).thenReturn(Optional.of(login));
        when(this.users.findById(3L)).thenReturn(Optional.of(user));

        assertThat(this.service.signIn("google", "sub", "ignored", false, "")).contains(user);
        assertThat(login.getLastLoginAt()).isEqualTo(NOW);
        verify(this.logins).save(login);
    }

    @Test
    void firstLoginWithUnverifiedEmailIsRejected() {
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));
        when(this.logins.findByClientIdAndProviderUserId(7L, "sub")).thenReturn(Optional.empty());

        assertThat(this.service.signIn("google", "sub", "a" + '@' + "b.test", false, "A")).isEmpty();
        verify(this.users, never()).save(any());
    }

    @Test
    void firstLoginWithBlankEmailIsRejected() {
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));
        when(this.logins.findByClientIdAndProviderUserId(7L, "sub")).thenReturn(Optional.empty());

        assertThat(this.service.signIn("google", "sub", "", true, "A")).isEmpty();
    }

    @Test
    void firstLoginCreatesAVerifiedUserNamedAfterTheGoogleProfileAndLinksIt() {
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));
        when(this.logins.findByClientIdAndProviderUserId(7L, "sub")).thenReturn(Optional.empty());
        when(this.users.findByEmail("ada" + '@' + "b.test")).thenReturn(Optional.empty());
        when(this.encoder.encode(any())).thenReturn("{bcrypt}random");
        when(this.users.save(any(User.class))).thenAnswer(call -> withId(call.getArgument(0), 11L));

        final Optional<User> result = this.service.signIn("google", "sub", " ADA" + '@' + "b.test ", true, " Ada L ");

        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("Ada L");
        assertThat(result.get().getPassword()).isEqualTo("{bcrypt}random");
        assertThat(result.get().getVerifiedAt()).isEqualTo(NOW);
        final ArgumentCaptor<OAuthLogin> saved = ArgumentCaptor.forClass(OAuthLogin.class);
        verify(this.logins).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(11L);
        assertThat(saved.getValue().getClientId()).isEqualTo(7L);
        assertThat(saved.getValue().getProviderUserId()).isEqualTo("sub");
    }

    @Test
    void blankProfileNameFallsBackToTheEmailLocalPart() {
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));
        when(this.logins.findByClientIdAndProviderUserId(7L, "sub")).thenReturn(Optional.empty());
        when(this.users.findByEmail("grace" + '@' + "b.test")).thenReturn(Optional.empty());
        when(this.encoder.encode(any())).thenReturn("{bcrypt}random");
        when(this.users.save(any(User.class))).thenAnswer(call -> withId(call.getArgument(0), 12L));

        assertThat(this.service.signIn("google", "sub", "grace" + '@' + "b.test", true, ""))
                .get().extracting(User::getUsername).isEqualTo("grace");
    }

    @Test
    void linkingAnUnconfirmedPasswordAccountConfirmsItAndDiscardsItsPassword() {
        final User existing = withId(new User("a" + '@' + "b.test", "A", "attacker-chosen"), 4L);
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));
        when(this.logins.findByClientIdAndProviderUserId(7L, "sub")).thenReturn(Optional.empty());
        when(this.users.findByEmail("a" + '@' + "b.test")).thenReturn(Optional.of(existing));
        when(this.encoder.encode(any())).thenReturn("{bcrypt}random");
        when(this.users.save(existing)).thenReturn(existing);

        assertThat(this.service.signIn("google", "sub", "a" + '@' + "b.test", true, "A")).contains(existing);
        assertThat(existing.getPassword()).isEqualTo("{bcrypt}random");
        assertThat(existing.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void linkingAConfirmedAccountKeepsItsPassword() {
        final User existing = withId(new User("a" + '@' + "b.test", "A", "my-hash"), 5L);
        existing.setVerifiedAt(NOW.minusDays(5));
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));
        when(this.logins.findByClientIdAndProviderUserId(7L, "sub")).thenReturn(Optional.empty());
        when(this.users.findByEmail("a" + '@' + "b.test")).thenReturn(Optional.of(existing));

        assertThat(this.service.signIn("google", "sub", "a" + '@' + "b.test", true, "A")).contains(existing);
        assertThat(existing.getPassword()).isEqualTo("my-hash");
        verify(this.users, never()).save(any());
        verify(this.logins).save(any(OAuthLogin.class));
    }

    @Test
    void userThatCouldNotBePersistedIsNotLinked() {
        when(this.clients.findByRegistrationId("google")).thenReturn(Optional.of(this.google));
        when(this.logins.findByClientIdAndProviderUserId(7L, "sub")).thenReturn(Optional.empty());
        when(this.users.findByEmail("q" + '@' + "b.test")).thenReturn(Optional.empty());
        // A saved user without an id (should never happen) must not produce a dangling link.
        when(this.encoder.encode(any())).thenReturn("{bcrypt}random");
        when(this.users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(this.service.signIn("google", "sub", "q" + '@' + "b.test", true, "Q")).isEmpty();
    }
}
