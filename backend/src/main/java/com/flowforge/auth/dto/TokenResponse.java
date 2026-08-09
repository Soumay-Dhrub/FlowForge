package com.flowforge.auth.dto;

/**
 * Response payload carrying a freshly issued token pair.
 *
 * @param accessToken  short-lived JWT access token
 * @param refreshToken long-lived refresh token (single-use; rotated on every refresh)
 * @param tokenType    always {@code Bearer}
 * @param expiresIn    access token lifetime in seconds
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
    public static final String BEARER = "Bearer";

    public static TokenResponse bearer(String accessToken, String refreshToken, long expiresInSeconds) {
        return new TokenResponse(accessToken, refreshToken, BEARER, expiresInSeconds);
    }
}
