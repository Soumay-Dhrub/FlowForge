package com.flowforge.auth;

import com.flowforge.auth.dto.LoginRequest;
import com.flowforge.auth.dto.LogoutRequest;
import com.flowforge.auth.dto.PasswordResetConfirmRequest;
import com.flowforge.auth.dto.PasswordResetRequest;
import com.flowforge.auth.dto.RefreshRequest;
import com.flowforge.auth.dto.TokenResponse;
import com.flowforge.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. All routes under {@code /api/auth/**} are public
 * (see {@link SecurityConfig}); authorization is established by the tokens they issue.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Authenticate with email and password and receive an access/refresh token pair.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse tokens = authService.login(request.email(), request.password());
        return ResponseEntity.ok(ApiResponse.success(tokens));
    }

    /**
     * Exchange a refresh token for a new token pair. The presented refresh token is invalidated.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenResponse tokens = authService.refreshToken(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(tokens));
    }

    /**
     * Invalidate the presented refresh token. Idempotent.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success("Logged out", null));
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiResponse<Void>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        authService.requestPasswordReset(request.email());
        return ResponseEntity.ok(ApiResponse.success(AuthService.PASSWORD_RESET_REQUESTED_MESSAGE, null));
    }

    /**
     * Apply a new password using a reset token (Requirements 5.3, 5.4, 5.5).
     *
     * <p>Unknown, expired or already-used tokens yield 400 Bad Request.</p>
     */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.confirmPasswordReset(request.token(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.success("Password updated", null));
    }
}
