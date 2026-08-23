package com.cde.platform.security;

import com.cde.platform.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies the bearer tokens that authenticate API and STOMP
 * clients.
 *
 * <p>The signing key arrives via {@link JwtProperties}, which refuses to bind
 * a missing, short, or previously-published secret — so this class can treat
 * the key as sound rather than re-checking it on every call.
 */
@Service
public class JwtTokenService {

    static final String TENANT_CLAIM = "tid";

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtTokenService(JwtProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expirationMs = properties.getExpirationMs();
    }

    /**
     * The tenant is carried in the token rather than looked up per request.
     * That makes it part of the signed payload — a caller cannot change which
     * tenant they are scoped to without invalidating the signature — and it
     * removes a database round trip from the path of every single request,
     * which would itself have to run before tenant context existed.
     */
    public String generateToken(String username, long tenantId) {
        return Jwts.builder()
            .subject(username)
            .claim(TENANT_CLAIM, tenantId)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(signingKey)
            .compact();
    }

    /**
     * @return the tenant the token was issued for, or empty for a token minted
     *         before the claim existed. Callers must treat empty as "no tenant
     *         context", which fails closed, rather than substituting a default.
     */
    public Optional<Long> extractTenantId(String token) {
        Object claim = Jwts.parser().verifyWith(signingKey).build()
            .parseSignedClaims(token).getPayload().get(TENANT_CLAIM);
        if (claim instanceof Number number) {
            return Optional.of(number.longValue());
        }
        return Optional.empty();
    }

    public String extractUsername(String token) {
        return Jwts.parser().verifyWith(signingKey).build()
            .parseSignedClaims(token).getPayload().getSubject();
    }

    public boolean isTokenValid(String token) {
        try {
            Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // Deliberately not logged: a rejected token is routine traffic, and
            // the token itself must never reach the log (§5.7).
            return false;
        }
    }
}
