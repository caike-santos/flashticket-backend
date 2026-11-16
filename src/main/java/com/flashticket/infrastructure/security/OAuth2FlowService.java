package com.flashticket.infrastructure.security;

import com.flashticket.domain.exception.SecurityAuthenticationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * TÓPICO (Fase 5): OAuth 2.0 & Fluxos OAuth (RFC 6749 e RFC 7636 PKCE).
 *
 * Implementa os 2 fluxos mais cobrados em arquitetura e entrevistas Backend:
 *
 * 1. Authorization Code Flow com PKCE (Proof Key for Code Exchange - S256):
 *    - Usado quando há um USUÁRIO HUMANO fazendo login via navegador/SPA/App Mobile
 *      (ex: "Entrar com Google/GitHub/Keycloak").
 *    - Passo 1 (/authorize): O app cliente gera um segredo aleatório (code_verifier)
 *      e envia apenas o hash SHA-256 Base64Url dele (code_challenge). O servidor devolve
 *      um 'authorization_code' de curta duração.
 *    - Passo 2 (/token): O app cliente envia o 'authorization_code' + o 'code_verifier' original.
 *      O servidor aplica SHA-256(code_verifier) e confere se bate com o 'code_challenge' do Passo 1.
 *      Assim, mesmo que um malware intercepte o 'authorization_code' na URL de redirect, ele não
 *      consegue trocá-lo por tokens sem o 'code_verifier'!
 *
 * 2. Client Credentials Flow (grant_type=client_credentials):
 *    - Usado para comunicação MÁQUINA-A-MÁQUINA (M2M / Backend-para-Backend), onde NÃO existe
 *      usuário humano (ex: servidor parceiro de pagamentos ou ERP consultando lotes).
 *    - O parceiro envia client_id + client_secret e recebe um Access Token JWT com ROLE_PARTNER
 *      (sem Refresh Token, conforme RFC 6749 Seção 4.4.3).
 */
@Service
public class OAuth2FlowService {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, AuthorizationCodeRecord> authCodeStore = new ConcurrentHashMap<>();
    private final Map<String, PartnerClientCredentials> registeredPartners = Map.of(
            "flashticket-partner-gateway",
            new PartnerClientCredentials(
                    "flashticket-partner-gateway",
                    "partner-secret-2026",
                    List.of("ROLE_PARTNER"),
                    "tickets:read webhooks:write")
    );

    private final RefreshTokenRotationService refreshTokenRotationService;
    private final JwtTokenService jwtTokenService;

    public OAuth2FlowService(
            RefreshTokenRotationService refreshTokenRotationService,
            JwtTokenService jwtTokenService) {
        this.refreshTokenRotationService = refreshTokenRotationService;
        this.jwtTokenService = jwtTokenService;
    }

    public PkcePair generatePkcePairHelper() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String codeVerifier = BASE64_URL_ENCODER.encodeToString(randomBytes);
        String codeChallenge = computeS256CodeChallenge(codeVerifier);
        return new PkcePair(codeVerifier, codeChallenge, "S256");
    }

    public String createAuthorizationCodeWithPkce(
            String clientId,
            String userEmail,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String state) {
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new SecurityAuthenticationException("PKCE obrigatorio: informe o parametro code_challenge.");
        }
        if (!"S256".equalsIgnoreCase(codeChallengeMethod)) {
            throw new SecurityAuthenticationException("PKCE exige code_challenge_method=S256 (SHA-256).");
        }

        String code = "authcode_" + UUID.randomUUID().toString().replace("-", "");
        AuthorizationCodeRecord record = new AuthorizationCodeRecord(
                code,
                clientId,
                userEmail,
                redirectUri,
                codeChallenge,
                "S256",
                state,
                false,
                Instant.now().plusSeconds(300));
        authCodeStore.put(code, record);
        return code;
    }

    public RefreshTokenRotationService.TokenPair exchangeAuthorizationCode(
            String code,
            String codeVerifier,
            String clientId) {
        AuthorizationCodeRecord record = authCodeStore.get(code);
        if (record == null) {
            throw new SecurityAuthenticationException("Authorization Code inexistente ou invalido.");
        }
        if (record.used()) {
            authCodeStore.remove(code);
            throw new SecurityAuthenticationException("Authorization Code ja foi utilizado (uso unico)!");
        }
        if (Instant.now().isAfter(record.expiresAt())) {
            authCodeStore.remove(code);
            throw new SecurityAuthenticationException("Authorization Code expirado.");
        }
        if (!record.clientId().equals(clientId)) {
            throw new SecurityAuthenticationException("client_id nao corresponde ao Authorization Code.");
        }
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new SecurityAuthenticationException("PKCE obrigatorio: code_verifier ausente.");
        }

        String computedChallenge = computeS256CodeChallenge(codeVerifier);
        if (!MessageDigest.isEqual(
                computedChallenge.getBytes(StandardCharsets.UTF_8),
                record.codeChallenge().getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityAuthenticationException(
                    "Falha na verificacao PKCE: SHA256(code_verifier) nao corresponde ao code_challenge!");
        }

        // Marca o authorization_code como usado (uso único!)
        authCodeStore.put(code, record.markAsUsed());

        return refreshTokenRotationService.issueInitialTokenPair(
                record.userEmail(),
                List.of("ROLE_USER", "ROLE_OAUTH2_PKCE"),
                "openid profile tickets:purchase");
    }

    public ClientCredentialsTokenResponse authenticateClientCredentials(
            String clientId,
            String clientSecret,
            String requestedScope) {
        PartnerClientCredentials partner = registeredPartners.get(clientId);
        if (partner == null || !MessageDigest.isEqual(
                partner.clientSecret().getBytes(StandardCharsets.UTF_8),
                (clientSecret != null ? clientSecret : "").getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityAuthenticationException("Credenciais OAuth2 M2M (client_id / client_secret) invalidas.");
        }

        String effectiveScope = (requestedScope != null && !requestedScope.isBlank())
                ? requestedScope
                : partner.allowedScopes();

        String accessToken = jwtTokenService.generateAccessToken(
                partner.clientId(),
                partner.roles(),
                effectiveScope);

        return new ClientCredentialsTokenResponse(
                accessToken,
                "Bearer",
                900,
                effectiveScope,
                "client_credentials (Machine-to-Machine: sem Refresh Token conforme RFC 6749)");
    }

    public static String computeS256CodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return BASE64_URL_ENCODER.encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Erro ao calcular SHA-256 para PKCE", ex);
        }
    }

    public record PkcePair(String codeVerifier, String codeChallenge, String codeChallengeMethod) {
    }

    public record AuthorizationCodeRecord(
            String code,
            String clientId,
            String userEmail,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String state,
            boolean used,
            Instant expiresAt) {

        public AuthorizationCodeRecord markAsUsed() {
            return new AuthorizationCodeRecord(
                    code, clientId, userEmail, redirectUri, codeChallenge, codeChallengeMethod, state, true, expiresAt);
        }
    }

    public record PartnerClientCredentials(
            String clientId,
            String clientSecret,
            List<String> roles,
            String allowedScopes) {
    }

    public record ClientCredentialsTokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String scope,
            String flowDescription) {
    }
}
