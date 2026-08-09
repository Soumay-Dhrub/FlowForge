package com.flowforge.auth;

import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * JWT authentication filter that intercepts requests and validates JWT tokens.
 * 
 * <p>This filter:</p>
 * <ul>
 *   <li>Extracts the JWT token from the Authorization header</li>
 *   <li>Validates the token signature and expiry</li>
 *   <li>Verifies the user is active in the database</li>
 *   <li>Sets the SecurityContext with the authenticated user</li>
 * </ul>
 * 
 * <p>If the token is invalid or the user is inactive, the request proceeds
 * without authentication, allowing Spring Security to reject it based on
 * the endpoint's access rules.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String jwt = extractJwtFromRequest(request);

            if (jwt != null && jwtTokenProvider.validateToken(jwt)) {
                Claims claims = jwtTokenProvider.extractClaims(jwt);
                
                // Only process access tokens, not refresh tokens
                String tokenType = claims.get("type", String.class);
                if (!"access".equals(tokenType)) {
                    log.warn("Non-access token used for authentication: {}", tokenType);
                    filterChain.doFilter(request, response);
                    return;
                }

                UUID userId = UUID.fromString(claims.getSubject());
                
                // Verify user exists and is active
                User user = userRepository.findByIdAndIsActiveTrue(userId).orElse(null);
                
                if (user != null) {
                    // Create authentication token with role as authority
                    String role = claims.get("role", String.class);
                    SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role);
                    
                    UsernamePasswordAuthenticationToken authentication = 
                            new UsernamePasswordAuthenticationToken(
                                    userId,
                                    null,
                                    List.of(authority)
                            );
                    
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    
                    log.debug("Set authentication for user: {} with role: {}", userId, role);
                } else {
                    log.warn("User not found or inactive: {}", userId);
                }
            }
        } catch (Exception e) {
            log.error("Cannot set user authentication: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT token from the Authorization header.
     * 
     * <p>Expected format: "Bearer {token}"</p>
     * 
     * @param request the HTTP request
     * @return the JWT token string, or null if not present or malformed
     */
    private String extractJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
