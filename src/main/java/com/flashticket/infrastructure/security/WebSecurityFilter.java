package com.flashticket.infrastructure.security;

import com.flashticket.domain.exception.SecurityAuthenticationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TÓPICOS (Fase 5): Pipeline de Segurança HTTP (Security Filter Chain):
 * 1. Headers de Segurança contra XSS / Clickjacking / MIME-Sniffing (CSP, X-Content-Type-Options, X-Frame-Options)
 * 2. CORS (Cross-Origin Resource Sharing) com whitelist estrita de origens e suporte a Preflight (OPTIONS)
 * 3. Rate Limiter (Token Bucket) retornando HTTP 429 Too Many Requests
 * 4. Proteção contra CSRF (Double-Submit Cookie: Cookie 'XSRF-TOKEN' + Header 'X-XSRF-TOKEN') em rotas baseadas em Cookie
 * 5. Autenticação Stateless via JWT ('Authorization: Bearer <token>') em rotas protegidas
 */
@Component
public class WebSecurityFilter extends OncePerRequestFilter {

    public static final String JWT_CLAIMS_ATTRIBUTE = "AUTHENTICATED_JWT_CLAIMS";

    private static final Set<String> ALLOWED_CORS_ORIGINS = Set.of(
            "https://app.flashticket.com",
            "http://localhost:3000"
    );

    private final JwtTokenService jwtTokenService;
    private final RateLimitService rateLimitService;

    public WebSecurityFilter(
            JwtTokenService jwtTokenService,
            RateLimitService rateLimitService) {
        this.jwtTokenService = jwtTokenService;
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // =====================================================================
        // 1. HEADERS DE SEGURANÇA CONTRA XSS, CLICKJACKING E MIME SNIFFING
        // =====================================================================
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "0");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // =====================================================================
        // 2. CORS (CROSS-ORIGIN RESOURCE SHARING)
        // =====================================================================
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            if (!ALLOWED_CORS_ORIGINS.contains(origin)) {
                writeProblemJson(
                        response,
                        HttpStatus.FORBIDDEN,
                        "Origem CORS bloqueada",
                        "A origem '" + origin + "' nao esta autorizada na politica CORS do FlashTicket.",
                        request.getRequestURI());
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.setHeader(
                    "Access-Control-Allow-Headers",
                    "Authorization, Content-Type, X-API-Version, X-XSRF-TOKEN, X-Client-Key");
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Max-Age", "3600");
            response.setHeader("Vary", "Origin");

            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        }

        String path = request.getRequestURI();

        // =====================================================================
        // 3. RATE LIMITER (ALGORITMO TOKEN BUCKET)
        // =====================================================================
        if (path.equals("/api/v2/security-demo/rate-limited")) {
            String clientKey = resolveClientKey(request);
            RateLimitService.RateLimitDecision decision = rateLimitService.tryConsume(clientKey);
            response.setHeader("X-Rate-Limit-Limit", String.valueOf(decision.limitCapacity()));
            response.setHeader("X-Rate-Limit-Remaining", String.valueOf(decision.remainingTokens()));

            if (!decision.allowed()) {
                response.setHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(decision.retryAfterSeconds()));
                writeProblemJson(
                        response,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Limite de requisicoes excedido (Rate Limit 429)",
                        "Cliente '" + clientKey + "' excedeu o limite de " + decision.limitCapacity()
                                + " requisicoes por minuto. Tente novamente em "
                                + decision.retryAfterSeconds() + " segundos.",
                        path);
                return;
            }
        }

        // =====================================================================
        // 4. PROTEÇÃO CSRF (DOUBLE-SUBMIT COOKIE PATTERN)
        // Aplicada quando a autenticação ocorre via Cookie automático do navegador!
        // =====================================================================
        if (path.equals("/api/v2/security-demo/cookie-checkout")
                && "POST".equalsIgnoreCase(request.getMethod())) {
            String csrfCookie = extractCookieValue(request, "XSRF-TOKEN");
            String csrfHeader = request.getHeader("X-XSRF-TOKEN");

            if (csrfCookie == null || csrfCookie.isBlank()
                    || csrfHeader == null || csrfHeader.isBlank()
                    || !MessageDigest.isEqual(
                            csrfCookie.getBytes(StandardCharsets.UTF_8),
                            csrfHeader.getBytes(StandardCharsets.UTF_8))) {
                writeProblemJson(
                        response,
                        HttpStatus.FORBIDDEN,
                        "Ataque CSRF bloqueado (Token CSRF ausente ou invalido)",
                        "Requisicoes autenticadas por Cookie exigem o padrao Double-Submit Cookie: "
                                + "o header 'X-XSRF-TOKEN' deve corresponder exatamente ao cookie 'XSRF-TOKEN'.",
                        path);
                return;
            }
        }

        // =====================================================================
        // 5. AUTENTICAÇÃO STATELESS VIA JWT (Authorization: Bearer <token>)
        // =====================================================================
        if (path.equals("/api/v2/auth/me") || path.equals("/api/v2/security-demo/partner-only")) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                writeProblemJson(
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "Token Bearer ausente",
                        "Informe o header 'Authorization: Bearer <accessToken>' para acessar este recurso protegido.",
                        path);
                return;
            }

            String rawJwt = authHeader.substring("Bearer ".length()).trim();
            try {
                JwtTokenService.JwtClaims claims = jwtTokenService.validateAndParseToken(rawJwt);
                if (path.equals("/api/v2/security-demo/partner-only")
                        && !claims.roles().contains("ROLE_PARTNER")) {
                    writeProblemJson(
                            response,
                            HttpStatus.FORBIDDEN,
                            "Acesso negado (RBAC / Role insuficiente)",
                            "Este endpoint exige a role ROLE_PARTNER obtida via OAuth2 Client Credentials Flow.",
                            path);
                    return;
                }
                request.setAttribute(JWT_CLAIMS_ATTRIBUTE, claims);
            } catch (SecurityAuthenticationException ex) {
                writeProblemJson(
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "Token JWT invalido ou expirado",
                        ex.getMessage(),
                        path);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request) {
        String customKey = request.getHeader("X-Client-Key");
        if (customKey != null && !customKey.isBlank()) {
            return customKey.trim();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "anonymous";
    }

    private String extractCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeProblemJson(
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail,
            String instance) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json;charset=UTF-8");
        String body = String.format(
                "{\"type\":\"https://flashticket.com/errors/security\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"}",
                title.replace("\"", "\\\""),
                status.value(),
                detail.replace("\"", "\\\""),
                instance.replace("\"", "\\\""));
        response.getWriter().write(body);
    }
}
