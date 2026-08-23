package com.cde.platform.security;

import com.cde.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upgrades a stored password hash to the currently configured key derivation
 * function, in place, at the moment its owner next logs in successfully.
 *
 * <p>Spring Security's {@code DaoAuthenticationProvider} drives this: after a
 * password verifies, it asks the encoder whether the stored hash was produced
 * with outdated parameters and, if so, hands the freshly-encoded replacement
 * here to be persisted. The user is never prompted and nothing about their
 * session changes.
 *
 * <p>This is what makes raising the iteration count or moving from BCrypt to
 * PBKDF2 an operational change rather than a mass password reset — which
 * matters, because a forced reset is precisely the event that trains users to
 * pick weaker passwords.
 */
@Service
public class RehashingUserDetailsPasswordService implements UserDetailsPasswordService {

    private static final Logger log =
        LoggerFactory.getLogger(RehashingUserDetailsPasswordService.class);

    private final UserRepository userRepository;

    public RehashingUserDetailsPasswordService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @param newPassword the already-encoded replacement hash, supplied by
     *                    Spring Security. It is never the plaintext, and it is
     *                    never logged.
     */
    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        userRepository.findByUsername(user.getUsername()).ifPresent(stored -> {
            stored.setPassword(newPassword);
            userRepository.save(stored);
            // The username identifies whose credential moved; the hash itself
            // and any part of it stay out of the log (§5.7).
            log.info("Password hash upgraded to the configured KDF for user '{}'",
                     user.getUsername());
        });

        return org.springframework.security.core.userdetails.User
            .withUserDetails(user)
            .password(newPassword)
            .build();
    }
}
