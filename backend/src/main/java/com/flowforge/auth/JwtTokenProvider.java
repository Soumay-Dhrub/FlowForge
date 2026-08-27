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

@Component
@Slf4j
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    /** Shared by the placeholder secrets in application.yml, docker-compose.yml and .env.example. */
    private static final String PLACEHOLDER_SECRET_PREFIX = "change-me";

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiry-ms}") long accessTokenExpiryMs,
            @Value("${app.jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs
    ) {
        // Keys.hmacShaKeyFor rejects a secret shorter than 256 bits, but cannot tell that a
        // long one is published in this repository. Warn rather than refuse to start: the same
        // default is what lets `docker compose up` work unconfigured.
        if (secret.startsWith(PLACEHOLDER_SECRET_PREFIX)) {
            log.warn("SECURITY: app.jwt.secret is still the placeholder shipped with the repository. "
                    + "Tokens are signed with a publicly known key and can be forged. "
                    + "Set JWT_SECRET to a private random value of at least 32 characters.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = accessTokenExpiryMs;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs;
    }

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

    public Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public UUID extractUserId(String token) {
        Claims claims = extractClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    public String extractTokenType(String token) {
        Claims claims = extractClaims(token);
        return claims.get("type", String.class);
    }
}
