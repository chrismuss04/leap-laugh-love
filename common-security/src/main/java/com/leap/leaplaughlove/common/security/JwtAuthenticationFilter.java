package com.leap.leaplaughlove.common.security;

import io.jsonwebtoken.JwtException;
// Session Timeout & Revocation: an unavailable session store must fail closed.
import org.springframework.dao.DataAccessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Filter that authenticates requests using JWT tokens
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    // Session Timeout & Revocation
    private final ClientSessionValidator sessions;
    private final String allowedServicePurpose;

    /**
     * Constructs a new JwtAuthenticationFilter with the specified JwtService.
     * @param jwtService the JwtService used to parse and validate JWT tokens
     */
    public JwtAuthenticationFilter(JwtService jwtService, ClientSessionValidator sessions) {
        this(jwtService, sessions, null);
    }

    // Session Timeout & Revocation: each service explicitly allows only its own background token purpose.
    public JwtAuthenticationFilter(JwtService jwtService, ClientSessionValidator sessions,
                                   String allowedServicePurpose) {
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.allowedServicePurpose = allowedServicePurpose;
    }

    /**
     * Filters incoming HTTP requests and authenticates them using JWT tokens.
     * @param request the HTTP request
     * @param response the HTTP response
     * @param filterChain the filter chain
     * @throws ServletException if an error occurs during filtering
     * @throws IOException if an I/O error occurs during filtering
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                // Session Timeout & Revocation: legacy tokens without a session cannot authenticate.
                var identity = jwtService.parseIdentity(token);
                if (identity.purpose() != null) {
                    if (!isAllowedServiceRequest(identity, request)) {
                        throw new JwtException("Token is not valid for this endpoint");
                    }
                } else if (!sessions.isActive(identity.sessionId(), identity.clientId(), identity.expiresAt())) {
                    throw new JwtException("Session is expired or revoked");
                }
                UUID clientId = identity.clientId();
                var authentication = new UsernamePasswordAuthenticationToken(
                        clientId, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
                authentication.setDetails(identity);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            } catch (DataAccessException e) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    // Session Timeout & Revocation: these tokens cannot access client-facing APIs or other services.
    private boolean isAllowedServiceRequest(JwtService.TokenIdentity identity, HttpServletRequest request) {
        if (allowedServicePurpose == null || !allowedServicePurpose.equals(identity.purpose())) return false;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("market-history".equals(identity.purpose())) {
            return "GET".equals(request.getMethod())
                    && path.matches("/api/marketdata/prices/[^/]+/history");
        }
        String endpoint = "GET".equals(request.getMethod()) ? "validation-data"
                : "POST".equals(request.getMethod()) ? "settlement" : "";
        return !endpoint.isEmpty() && path.matches(
                "/api/account/internal/accounts/[0-9a-fA-F-]{36}/" + endpoint);
    }
}

