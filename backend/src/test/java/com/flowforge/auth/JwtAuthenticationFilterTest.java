package com.flowforge.auth;

import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JwtAuthenticationFilter.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FilterChain filterChain;

    private JwtTokenProvider jwtTokenProvider;
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Clear security context before each test
        SecurityContextHolder.clearContext();

        // Initialize JWT provider with test configuration
        String testSecret = "test-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm";
        jwtTokenProvider = new JwtTokenProvider(testSecret, 900000, 2592000000L);

        // Create filter instance
        jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtTokenProvider, userRepository);

        // Create test user
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
    void doFilterInternal_shouldAuthenticateWithValidToken() throws ServletException, IOException {
        // Given
        String token = jwtTokenProvider.generateAccessToken(testUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer " + token);

        when(userRepository.findByIdAndIsActiveTrue(testUser.getId()))
                .thenReturn(Optional.of(testUser));

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(testUser.getId());
        assertThat(authentication.getAuthorities()).hasSize(1);
        assertThat(authentication.getAuthorities().iterator().next().getAuthority())
                .isEqualTo("ROLE_ADMIN");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_shouldNotAuthenticateWithoutToken() throws ServletException, IOException {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNull();

        verify(filterChain).doFilter(request, response);
        verify(userRepository, never()).findByIdAndIsActiveTrue(any());
    }

    @Test
    void doFilterInternal_shouldNotAuthenticateWithInvalidToken() throws ServletException, IOException {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer invalid.token.here");

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNull();

        verify(filterChain).doFilter(request, response);
        verify(userRepository, never()).findByIdAndIsActiveTrue(any());
    }

    @Test
    void doFilterInternal_shouldNotAuthenticateWithRefreshToken() throws ServletException, IOException {
        // Given
        String refreshToken = jwtTokenProvider.generateRefreshToken(testUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer " + refreshToken);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNull();

        verify(filterChain).doFilter(request, response);
        verify(userRepository, never()).findByIdAndIsActiveTrue(any());
    }

    @Test
    void doFilterInternal_shouldNotAuthenticateInactiveUser() throws ServletException, IOException {
        // Given
        String token = jwtTokenProvider.generateAccessToken(testUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer " + token);

        when(userRepository.findByIdAndIsActiveTrue(testUser.getId()))
                .thenReturn(Optional.empty());

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNull();

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_shouldHandleMissingBearerPrefix() throws ServletException, IOException {
        // Given
        String token = jwtTokenProvider.generateAccessToken(testUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", token); // Missing "Bearer " prefix

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNull();

        verify(filterChain).doFilter(request, response);
        verify(userRepository, never()).findByIdAndIsActiveTrue(any());
    }

    @Test
    void doFilterInternal_shouldContinueFilterChainOnException() throws ServletException, IOException {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer malformed-token-that-causes-exception");

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNull();

        // Filter chain should still be invoked
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_shouldExtractCorrectRoleAuthority() throws ServletException, IOException {
        // Given
        Role managerRole = Role.builder()
                .id(UUID.randomUUID())
                .name("MANAGER")
                .permissions(new HashMap<>())
                .build();

        User managerUser = User.builder()
                .id(UUID.randomUUID())
                .name("Manager User")
                .email("manager@example.com")
                .passwordHash("hashedPassword")
                .role(managerRole)
                .isActive(true)
                .build();

        String token = jwtTokenProvider.generateAccessToken(managerUser);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer " + token);

        when(userRepository.findByIdAndIsActiveTrue(managerUser.getId()))
                .thenReturn(Optional.of(managerUser));

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).hasSize(1);
        assertThat(authentication.getAuthorities().iterator().next().getAuthority())
                .isEqualTo("ROLE_MANAGER");

        verify(filterChain).doFilter(request, response);
    }
}
