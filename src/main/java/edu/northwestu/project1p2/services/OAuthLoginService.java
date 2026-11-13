package edu.northwestu.project1p2.services;

import edu.northwestu.project1p2.entities.OAuthClient;
import edu.northwestu.project1p2.entities.OAuthLogin;
import edu.northwestu.project1p2.entities.User;
import edu.northwestu.project1p2.repositories.OAuthClientRepository;
import edu.northwestu.project1p2.repositories.OAuthLoginRepository;
import edu.northwestu.project1p2.repositories.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps a third-party identity (for example a Google account) to a local {@link User}.
 *
 * <p>The provider's stable subject id is the key. The first time an identity is
 * seen it is linked, through {@code oauth_logins}, to the local user with the same
 * confirmed email address, or to a newly created user.
 */
@Service
public class OAuthLoginService {

    private final OAuthClientRepository clientRepository;
    private final OAuthLoginRepository loginRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public OAuthLoginService(final OAuthClientRepository clientRepository,
                             final OAuthLoginRepository loginRepository,
                             final UserRepository userRepository,
                             final PasswordEncoder passwordEncoder,
                             final Clock clock) {
        this.clientRepository = clientRepository;
        this.loginRepository = loginRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Returns the local user for a provider identity, linking or creating it on first use.
     *
     * @param registrationId  Spring Security client registration id, e.g. {@code google}
     * @param subject         the provider's stable user id (OIDC {@code sub})
     * @param email           the email address the provider reports
     * @param emailVerified   whether the provider has verified that address
     * @param displayName     the name the provider reports, may be blank
     * @return the user, or empty when the identity cannot be trusted
     *         (unknown provider, or an unverified email on an identity not seen before)
     */
    @Transactional
    public Optional<User> signIn(final String registrationId, final String subject, final String email,
                                 final boolean emailVerified, final String displayName) {
        final Optional<Long> clientId = this.clientRepository.findByRegistrationId(registrationId)
                .map(OAuthClient::getId);
        if (clientId.isEmpty() || subject.isBlank()) {
            return Optional.empty();
        }
        final LocalDateTime now = LocalDateTime.now(this.clock);
        final Optional<OAuthLogin> existing =
                this.loginRepository.findByClientIdAndProviderUserId(clientId.get(), subject);
        // First sight of an identity: only trust the email if the provider verified it.
        return existing.isPresent()
                ? this.returning(existing.get(), now)
                : this.firstLogin(clientId.get(), subject, email, emailVerified, displayName, now);
    }

    private Optional<User> returning(final OAuthLogin login, final LocalDateTime now) {
        login.recordLogin(now);
        this.loginRepository.save(login);
        return this.userRepository.findById(login.getUserId());
    }

    private Optional<User> firstLogin(final Long clientId, final String subject, final String email,
                                      final boolean emailVerified, final String displayName,
                                      final LocalDateTime now) {
        Optional<User> result = Optional.empty();
        if (emailVerified && !email.isBlank()) {
            final User user = this.linkableUser(EmailAddresses.normalize(email), displayName, now);
            final Long userId = user.getId();
            if (userId != null) {
                this.loginRepository.save(new OAuthLogin(userId, clientId, subject, now, now));
                result = Optional.of(user);
            }
        }
        return result;
    }

    private User linkableUser(final String email, final String displayName, final LocalDateTime now) {
        final User user = this.userRepository.findByEmail(email).orElseGet(() -> new User(
                email,
                displayName.isBlank() ? email.substring(0, email.indexOf('@')) : displayName.trim(),
                this.unusablePassword()));
        User result = user;
        if (user.getVerifiedAt() == null) {
            // The provider proved ownership of this address. Whoever registered it by
            // password never did, so discard that password and confirm the account.
            user.setPassword(this.unusablePassword());
            user.setVerifiedAt(now);
            result = this.userRepository.save(user);
        }
        return result;
    }

    /** A hash of a random secret nobody knows: the account can sign in only through its provider. */
    private String unusablePassword() {
        return this.passwordEncoder.encode(UUID.randomUUID().toString() + UUID.randomUUID());
    }
}
