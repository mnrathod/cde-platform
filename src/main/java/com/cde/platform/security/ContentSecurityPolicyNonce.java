package com.cde.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * One unguessable value per request, shared by the policy header and the
 * document it governs.
 *
 * <p>A nonce only works if the two agree. The header writer runs in the
 * security filter chain and the controller runs after it, so neither can
 * generate the value and hand it to the other; this filter runs before both
 * and puts it where each can read it.
 *
 * <p>It is generated for <em>every</em> request rather than only the ones that
 * will use it. Deciding which paths serve a document would mean keeping a
 * second copy of that rule in step with the one in {@code SecurityConfig}, and
 * the two drifting apart fails in the direction that matters: a document served
 * with a policy naming a nonce nobody put in the markup renders unstyled. Sixteen
 * bytes from a seeded {@link SecureRandom} is not a cost worth that risk.
 */
public final class ContentSecurityPolicyNonce extends OncePerRequestFilter {

    /** Where both the header writer and the controller look. */
    public static final String REQUEST_ATTRIBUTE = "cde.contentSecurityPolicy.nonce";

    // 16 bytes. The CSP specification asks only that a nonce be unguessable
    // and at least 128 bits of entropy is the usual reading of that.
    private static final int NONCE_BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getEncoder().withoutPadding();

    /**
     * Read the nonce for the request in flight.
     *
     * @return the value, or an empty string when this filter did not run — a
     *         caller must render the empty case rather than a literal "null",
     *         which would be a syntactically valid nonce matching nothing.
     */
    public static String currentNonce(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String nonce ? nonce : "";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        request.setAttribute(REQUEST_ATTRIBUTE, encoder.encodeToString(bytes));
        chain.doFilter(request, response);
    }
}
