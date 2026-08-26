package com.flowforge.auth;

import com.flowforge.user.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT token provider for generating and validating access and refresh tokens.
 * 
 * <p>Access tokens are short-lived (default 15 minutes) and contain user identity and role claims.
 * Refresh tokens are long-lived (default 7 days) and are used to obtain new access tokens.</p>
 * 
 * <p>Both token types are signed with HMAC-SHA256 using the configured secret key.</p>
 * 
 * <p>Token values are never logged.</p>
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    /**
     * Prefix shared by every placeholder secret shipped in the repository — {@code application.yml},
     * {@code docker-compose.yml} and {@code .env.example} each carry a different
     * {@code change-me-in-production-...} string. Matching the prefix catches all of them.
     */
    private static final String PLACEHOLDER_SECRET_PREFIX = "change-me";

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiry-ms}") long accessTokenExpiryMs,
            @Value("${app.jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs
    ) {
        // Keys.hmacShaKeyFor already rejects anything shorter than 256 bits, so a too-short secret
        // fails fast at startup. It cannot judge whether a long secret is *public*, though, and the
        // committed default satisfies the length rule — so a deployment that never sets JWT_SECRET
        // starts up happily and signs tokens with a key published in this repository. Anyone could
        // then mint a valid admin token. Warn loudly rather than refuse to start: the same default
        // is what makes `docker compose up` work with no configuration, and breaking that would
        // trade a documented warning for a setup barrier.
        if (secret.startsWith(PLACEHOLDER_SECRET_PREFIX)) {
            log.warn("""
                    SECURITY: app.jwt.secret is still the placeholder shipped with the repository. \
                    Tokens are being signed with a publicly known key, so anyone can forge one. \
                    Set the JWT_SECRET environment variable to a private random value of at least \
                    32 characters before exposing this instance to anyone.""");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = accessTokenExpiryMs;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs;
    }

    /**
     * Generate a JWT access token for the given user.
     * 
     * <p>The token contains:</p>
     * <ul>
     *   <li>sub: user ID</li>
     *   <li>email: user email</li>
     *   <li>role: user role name</li>
     *   <li>type: "access"</li>
     *   <li>iat: issued at timestamp</li>
     *   <li>exp: expiration timestamp</li>
     * </ul>
     * 
     * @param user the authenticated user
     * @return signed JWT access token string
     */
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(accessTokenExpiryMs);

        return Jwts.builder()
                .setSubject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().getName())
                .claim("type", "access")
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiry))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Generate a JWT refresh token for the given user.
     * 
     * <p>The token contains:</p>
     * <ul>
     *   <li>sub: user ID</li>
     *   <li>type: "refresh"</li>
     *   <li>jti: unique token ID (for revocation tracking)</li>
     *   <li>iat: issued at timestamp</li>
     *   <li>exp: expiration timestamp</li>
     * </ul>
     * 
     * @param user the authenticated user
     * @return signed JWT refresh token string
     */
    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(refreshTokenExpiryMs);

        return Jwts.builder()
                .setSubject(user.getId().toString())
                .claim("type", "refresh")
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiry))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Validate a JWT token and return true if valid, false otherwise.
     * 
     * <p>A token is considered valid if:</p>
     * <ul>
     *   <li>The signature is valid</li>
     *   <li>The token has not expired</li>
     *   <li>The token can be parsed without errors</li>
     * </ul>
     * 
     * @param token the JWT token string
     * @return true if valid, false otherwise
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (SignatureException e) {
            // Never log the token value itself — only the failure reason.
            log.debug("JWT rejected: invalid signature");
        } catch (MalformedJwtException e) {
            log.debug("JWT rejected: malformed token");
        } catch (ExpiredJwtException e) {
            log.debug("JWT rejected: token expired");
        } catch (UnsupportedJwtException e) {
            log.debug("JWT rejected: unsupported token");
        } catch (IllegalArgumentException e) {
            log.debug("JWT rejected: empty or null token");
        }
        return false;
    }

    /**
     * Extract all claims from a JWT token.
     * 
     * <p>This method assumes the token has already been validated.
     * If the token is invalid, this method will throw an exception.</p>
     * 
     * @param token the JWT token string
     * @return the claims contained in the token
     * @throws JwtException if the token cannot be parsed
     */
    public Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract the user ID from a JWT token.
     * 
     * @param token the JWT token string
     * @return the user ID as a UUID
     */
    public UUID extractUserId(String token) {
        Claims claims = extractClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Extract the token type from a JWT token.
     * 
     * @param token the JWT token string
     * @return the token type ("access" or "refresh")
     */
    public String extractTokenType(String token) {
        Claims claims = extractClaims(token);
        return claims.get("type", String.class);
    }
}
