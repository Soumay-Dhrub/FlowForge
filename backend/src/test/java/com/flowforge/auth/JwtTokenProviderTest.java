package com.flowforge.auth;

import com.flowforge.user.Role;
import com.flowforge.user.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JwtTokenProvider.
 */
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Use a test secret and short expiry times for testing
        String testSecret = "test-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm";
        long accessTokenExpiry = 15 * 60 * 1000; // 15 minutes
        long refreshTokenExpiry = 7 * 24 * 60 * 60 * 1000; // 7 days

        jwtTokenProvider = new JwtTokenProvider(testSecret, accessTokenExpiry, refreshTokenExpiry);

        // Create a test user
        Role role = Role.builder()
                .id(UUID.randomUUID())
                .name("ADMIN")
                .permissions(new HashMap<>())
                .build();

        testUser = User.builder()
                .id(UUID.randomUUID())
                .name("Test User")
                .email("test@example.com")
                .passwordHash("hashedPassword")
                .role(role)
                .isActive(true)
                .build();
    }

    @Test
    void generateAccessToken_shouldCreateValidToken() {
        // When
        String token = jwtTokenProvider.generateAccessToken(testUser);

        // Then
        assertThat(token).isNotNull().isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void generateAccessToken_shouldContainCorrectClaims() {
        // When
        String token = jwtTokenProvider.generateAccessToken(testUser);
        Claims claims = jwtTokenProvider.extractClaims(token);

        // Then
        assertThat(claims.getSubject()).isEqualTo(testUser.getId().toString());
        assertThat(claims.get("email", String.class)).isEqualTo(testUser.getEmail());
        assertThat(claims.get("role", String.class)).isEqualTo(testUser.getRole().getName());
        assertThat(claims.get("type", String.class)).isEqualTo("access");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void generateRefreshToken_shouldCreateValidToken() {
        // When
        String token = jwtTokenProvider.generateRefreshToken(testUser);

        // Then
        assertThat(token).isNotNull().isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void generateRefreshToken_shouldContainCorrectClaims() {
        // When
        String token = jwtTokenProvider.generateRefreshToken(testUser);
        Claims claims = jwtTokenProvider.extractClaims(token);

        // Then
        assertThat(claims.getSubject()).isEqualTo(testUser.getId().toString());
        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
        assertThat(claims.getId()).isNotNull(); // JTI for revocation tracking
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void validateToken_shouldReturnTrueForValidToken() {
        // Given
        String token = jwtTokenProvider.generateAccessToken(testUser);

        // When
        boolean isValid = jwtTokenProvider.validateToken(token);

        // Then
        assertThat(isValid).isTrue();
    }

    @Test
    void validateToken_shouldReturnFalseForMalformedToken() {
        // Given
        String malformedToken = "not.a.valid.jwt";

        // When
        boolean isValid = jwtTokenProvider.validateToken(malformedToken);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    void validateToken_shouldReturnFalseForEmptyToken() {
        // When
        boolean isValid = jwtTokenProvider.validateToken("");

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    void validateToken_shouldReturnFalseForExpiredToken() {
        // Given — a provider whose access tokens are already past their expiry
        JwtTokenProvider expiredProvider = new JwtTokenProvider(
                "test-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm",
                -1000L,
                -1000L);
        String expiredToken = expiredProvider.generateAccessToken(testUser);

        // When / Then
        assertThat(expiredProvider.validateToken(expiredToken)).isFalse();
    }

    @Test
    void validateToken_shouldReturnFalseForTamperedPayload() {
        // Given — a valid token whose payload segment has been altered
        String token = jwtTokenProvider.generateAccessToken(testUser);
        String[] parts = token.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("\"role\":\"ADMIN\"", "\"role\":\"SUPER\"")
                        .getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        // When / Then — signature no longer matches the payload
        assertThat(tamperedToken).isNotEqualTo(token);
        assertThat(jwtTokenProvider.validateToken(tamperedToken)).isFalse();
    }

    @Test
    void validateToken_shouldReturnFalseForTokenSignedWithDifferentSecret() {
        // Given — a token signed by a provider using a different secret
        JwtTokenProvider foreignProvider = new JwtTokenProvider(
                "a-completely-different-secret-key-that-is-also-256-bits-long-ok",
                900000L,
                604800000L);
        String foreignToken = foreignProvider.generateAccessToken(testUser);

        // When / Then
        assertThat(jwtTokenProvider.validateToken(foreignToken)).isFalse();
    }

    @Test
    void extractUserId_shouldReturnCorrectUserId() {
        // Given
        String token = jwtTokenProvider.generateAccessToken(testUser);

        // When
        UUID extractedUserId = jwtTokenProvider.extractUserId(token);

        // Then
        assertThat(extractedUserId).isEqualTo(testUser.getId());
    }

    @Test
    void extractTokenType_shouldReturnAccessForAccessToken() {
        // Given
        String token = jwtTokenProvider.generateAccessToken(testUser);

        // When
        String tokenType = jwtTokenProvider.extractTokenType(token);

        // Then
        assertThat(tokenType).isEqualTo("access");
    }

    @Test
    void extractTokenType_shouldReturnRefreshForRefreshToken() {
        // Given
        String token = jwtTokenProvider.generateRefreshToken(testUser);

        // When
        String tokenType = jwtTokenProvider.extractTokenType(token);

        // Then
        assertThat(tokenType).isEqualTo("refresh");
    }

    @Test
    void accessTokenAndRefreshToken_shouldHaveDifferentValues() {
        // When
        String accessToken = jwtTokenProvider.generateAccessToken(testUser);
        String refreshToken = jwtTokenProvider.generateRefreshToken(testUser);

        // Then
        assertThat(accessToken).isNotEqualTo(refreshToken);
    }

    @Test
    void generateAccessToken_shouldCreateUniqueTokensForSameUser() {
        // When
        String token1 = jwtTokenProvider.generateAccessToken(testUser);
        
        // Add delay to ensure different timestamp (at least 1 second for timestamp change)
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        String token2 = jwtTokenProvider.generateAccessToken(testUser);

        // Then - tokens should be different due to different issuedAt timestamps
        assertThat(token1).isNotEqualTo(token2);
    }
}
