package com.student.studentservice.filter;

import com.student.studentservice.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@AllArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    // Endpoints a user still on their temp password is allowed to call.
    private static final Set<String> PASSWORD_CHANGE_ALLOWED_PATHS =
            Set.of("/auth/change-password", "/auth/logout", "/auth/refresh");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Step 1 - Extract Authorization header
        String authHeader = request.getHeader("Authorization");

        // Step 2 - If no token, skip filter (let security config handle it)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Step 3 - Extract token
        String token = authHeader.substring(7);

        try {
            // Step 4 - Check token type (must be ACCESS, not REFRESH)
            String tokenType = jwtUtil.extractTokenType(token);
            if (!"ACCESS".equals(tokenType)) {
                log.warn("Invalid token type used: {}", tokenType);
                filterChain.doFilter(request, response);
                return;
            }

            // Step 5 - Extract email and role
            String email = jwtUtil.extractEmail(token);
            String role = jwtUtil.extractRole(token);

            // Step 6 - Validate token
            if (email != null && jwtUtil.validateToken(token, email)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                // Step 7 - Set authentication in security context
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                email,
                                null,
                                List.of(new SimpleGrantedAuthority(role))
                        );

                // Step 7a - Users on a temp password may only change it, refresh, or log out
                // Path relative to the context root; getServletPath() is empty under some
                // servlet mappings (and in MockMvc), so derive it from the request URI instead.
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (jwtUtil.isPasswordChangeRequired(token) && !PASSWORD_CHANGE_ALLOWED_PATHS.contains(path)) {
                    log.warn("Password change required before accessing {}: {}", path, email);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"status\":403,\"error\":"
                            + "\"Password change required. Please change your temporary password first.\"}");
                    return;
                }

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.info("Authentication set for: {}", email);
            }

        } catch (Exception e) {
            log.error("JWT Filter error: {}", e.getMessage());
        }

        // Step 8 - Continue filter chain
        filterChain.doFilter(request, response);
    }
}