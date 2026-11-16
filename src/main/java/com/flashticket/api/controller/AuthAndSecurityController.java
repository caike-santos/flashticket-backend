package com.flashticket.api.controller;

import com.flashticket.domain.exception.SecurityAuthenticationException;
import com.flashticket.infrastructure.security.JwtTokenService;
import com.flashticket.infrastructure.security.OAuth2FlowService;
import com.flashticket.infrastructure.security.RateLimitService;
import com.flashticket.infrastructure.security.RefreshTokenRotationService;
import com.flashticket.infrastructure.security.WebSecurityFilter;
import com.flashticket.infrastructure.security.XssSanitizer;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TÓPICOS (Fase 5): Endpoints práticos de Segurança de Ponta a Ponta:
 * - Login Stateless com emissão de JWT (HS256) + Refresh Token
 * - Rotação de Refresh Token com detecção de roubo/reuso em cadeia (Token Family Revocation)
 * - OAuth 2.0: Authorization Code Flow com PKCE (S256) e Client Credentials Flow (M2M)
 * - Proteção CSRF (Double-Submit Cookie) vs Autenticação via Header Bearer
 * - Sanitização contra XSS (Cross-Site Scripting)
 * - Rate Limiter (Token Bucket) com HTTP 429 Too Many Requests
 */
@RestController
@RequestMapping("/api/v2")
public class AuthAndSecurityController {

    private static final Map<String, DemoUser> DEMO_USERS = Map.of(
            "caike@flashticket.com",
            new DemoUser("caike@flashticket.com", "Flash@2026", List.of("ROLE_USER", "ROLE_ORGANIZER")),
            "admin@flashticket.com",
            new DemoUser("admin@flashticket.com", "Admin@2026", List.of("ROLE_ADMIN", "ROLE_ORGANIZER"))
    );

    private final RefreshTokenRotationService refreshTokenRotationService;
    private final OAuth2FlowService oauth2FlowService;
    private final RateLimitService rateLimitService;

    public AuthAndSecurityController(
            RefreshTokenRotationService refreshTokenRotationService,
            OAuth2FlowService oauth2FlowService,
            RateLimitService rateLimitService) {
        this.refreshTokenRotationService = refreshTokenRotationService;
        this.oauth2FlowService = oauth2FlowService;
        this.rateLimitService = rateLimitService;
    }

    // =========================================================================
    // 1. JWT STATELESS AUTH + REFRESH TOKEN ROTATION
    // =========================================================================

    @PostMapping("/auth/login")
    public ResponseEntity<RefreshTokenRotationService.TokenPair> login(@RequestBody LoginRequest request) {
        DemoUser user = DEMO_USERS.get(request.email());
        if (user == null || !user.password().equals(request.password())) {
            throw new SecurityAuthenticationException("Credenciais invalidas (e-mail ou senha incorretos).");
        }

        RefreshTokenRotationService.TokenPair tokenPair = refreshTokenRotationService.issueInitialTokenPair(
                user.email(),
                user.roles(),
                "events:read events:write orders:write");
        return ResponseEntity.ok(tokenPair);
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<RefreshTokenRotationService.TokenPair> refresh(@RequestBody RefreshRequest request) {
        RefreshTokenRotationService.TokenPair rotatedPair =
                refreshTokenRotationService.rotateRefreshToken(request.refreshToken());
        return ResponseEntity.ok(rotatedPair);
    }

    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> currentAuthenticatedUser(HttpServletRequest request) {
        JwtTokenService.JwtClaims claims =
                (JwtTokenService.JwtClaims) request.getAttribute(WebSecurityFilter.JWT_CLAIMS_ATTRIBUTE);
        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "subject", claims.subject(),
                "roles", claims.roles(),
                "scope", claims.scope(),
                "jwtId", claims.jti(),
                "issuedAtEpochSeconds", claims.issuedAtEpochSeconds(),
                "expiresAtEpochSeconds", claims.expiresAtEpochSeconds()
        ));
    }

    // =========================================================================
    // 2. OAUTH 2.0 FLOWS (AUTHORIZATION CODE + PKCE S256 & CLIENT CREDENTIALS)
    // =========================================================================

    @GetMapping("/auth/oauth2/pkce-helper")
    public ResponseEntity<OAuth2FlowService.PkcePair> generatePkceHelper() {
        return ResponseEntity.ok(oauth2FlowService.generatePkcePairHelper());
    }

    @PostMapping("/auth/oauth2/authorize")
    public ResponseEntity<Map<String, String>> authorizeWithPkce(@RequestBody OAuth2AuthorizeRequest request) {
        String code = oauth2FlowService.createAuthorizationCodeWithPkce(
                request.clientId(),
                request.userEmail(),
                request.redirectUri(),
                request.codeChallenge(),
                request.codeChallengeMethod(),
                request.state());

        return ResponseEntity.ok(Map.of(
                "authorizationCode", code,
                "state", request.state() != null ? request.state() : "",
                "redirectUri", request.redirectUri() + "?code=" + code
                        + (request.state() != null ? "&state=" + request.state() : ""),
                "nextStep", "Envie este 'authorizationCode' + o 'codeVerifier' original para POST /api/v2/auth/oauth2/token"
        ));
    }

    @PostMapping("/auth/oauth2/token")
    public ResponseEntity<?> exchangeOAuth2Token(@RequestBody OAuth2TokenRequest request) {
        if ("authorization_code".equalsIgnoreCase(request.grantType())) {
            RefreshTokenRotationService.TokenPair tokenPair = oauth2FlowService.exchangeAuthorizationCode(
                    request.code(),
                    request.codeVerifier(),
                    request.clientId());
            return ResponseEntity.ok(tokenPair);
        }

        if ("client_credentials".equalsIgnoreCase(request.grantType())) {
            OAuth2FlowService.ClientCredentialsTokenResponse m2mToken =
                    oauth2FlowService.authenticateClientCredentials(
                            request.clientId(),
                            request.clientSecret(),
                            request.scope());
            return ResponseEntity.ok(m2mToken);
        }

        throw new SecurityAuthenticationException(
                "grant_type nao suportado: '" + request.grantType()
                        + "'. Use 'authorization_code' ou 'client_credentials'.");
    }

    // =========================================================================
    // 3. DEMONSTRAÇÕES DE RBAC PARTNER, CSRF, XSS E RATE LIMITER
    // =========================================================================

    @GetMapping("/security-demo/partner-only")
    public ResponseEntity<Map<String, Object>> partnerOnlyResource(HttpServletRequest request) {
        JwtTokenService.JwtClaims claims =
                (JwtTokenService.JwtClaims) request.getAttribute(WebSecurityFilter.JWT_CLAIMS_ATTRIBUTE);
        return ResponseEntity.ok(Map.of(
                "partnerClientId", claims.subject(),
                "roles", claims.roles(),
                "grantedScope", claims.scope(),
                "message", "Acesso M2M autorizado via OAuth 2.0 Client Credentials Flow!"
        ));
    }

    @GetMapping("/security-demo/csrf-token")
    public ResponseEntity<Map<String, String>> issueCsrfTokenCookie(HttpServletResponse response) {
        String csrfToken = "xsrf_" + UUID.randomUUID().toString().replace("-", "");
        Cookie cookie = new Cookie("XSRF-TOKEN", csrfToken);
        cookie.setPath("/");
        // O cookie XSRF-TOKEN precisa ser lido pelo JavaScript do próprio domínio para ser enviado no header X-XSRF-TOKEN
        cookie.setHttpOnly(false);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of(
                "csrfToken", csrfToken,
                "cookieSet", "XSRF-TOKEN=" + csrfToken,
                "instructions", "Envie tanto o Cookie 'XSRF-TOKEN' quanto o Header 'X-XSRF-TOKEN' no POST /api/v2/security-demo/cookie-checkout"
        ));
    }

    @PostMapping("/security-demo/cookie-checkout")
    public ResponseEntity<Map<String, Object>> processCookieAuthenticatedCheckout(
            @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(Map.of(
                "status", "CHECKOUT_APPROVED_WITH_CSRF_PROTECTION",
                "csrfVerified", true,
                "payloadReceived", payload
        ));
    }

    @PostMapping("/security-demo/sanitize-xss")
    public ResponseEntity<Map<String, String>> sanitizeUntrustedHtml(@RequestBody Map<String, String> payload) {
        String rawInput = payload.getOrDefault("rawHtml", "");
        String sanitizedOutput = XssSanitizer.sanitize(rawInput);
        return ResponseEntity.ok(Map.of(
                "rawUntrustedInput", rawInput,
                "sanitizedSafeOutput", sanitizedOutput
        ));
    }

    @GetMapping("/security-demo/rate-limited")
    public ResponseEntity<Map<String, Object>> rateLimitedEndpoint(HttpServletRequest request) {
        return ResponseEntity.ok(Map.of(
                "status", "ALLOWED",
                "message", "Requisicao processada dentro da cota do Token Bucket!"
        ));
    }

    @PostMapping("/security-demo/rate-limited/reset")
    public ResponseEntity<Map<String, String>> resetRateLimiterBuckets() {
        rateLimitService.resetAllBuckets();
        return ResponseEntity.ok(Map.of(
                "message", "Todos os baldes (Token Buckets) do Rate Limiter foram reiniciados com sucesso!"
        ));
    }

    private record DemoUser(String email, String password, List<String> roles) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record OAuth2AuthorizeRequest(
            String clientId,
            String userEmail,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String state) {
    }

    public record OAuth2TokenRequest(
            String grantType,
            String clientId,
            String clientSecret,
            String code,
            String codeVerifier,
            String scope) {
    }
}
