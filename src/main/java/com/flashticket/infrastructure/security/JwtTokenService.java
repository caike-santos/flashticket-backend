package com.flashticket.infrastructure.security;

import com.flashticket.domain.exception.SecurityAuthenticationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TÓPICO (Fase 5): JWT (JSON Web Token — RFC 7519) para Autenticação Stateless.
 *
 * Um JWT é composto por 3 partes separadas por ponto (.):
 *   1. Header    (Base64Url): {"alg":"HS256","typ":"JWT"}
 *   2. Payload   (Base64Url): {"sub":"...","roles":"...","scope":"...","jti":"...","iat":...,"exp":...}
 *   3. Signature (Base64Url): HMAC_SHA256(headerBase64 + "." + payloadBase64, secretKey)
 *
 * Por que é "Stateless"? Porque o servidor NÃO precisa guardar sessão em memória nem consultar
 * o banco a cada requisição para saber quem é o usuário: basta validar a assinatura matemática!
 */
@Service
public class JwtTokenService {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secretKeyBytes;
    private final long accessTokenTtlSeconds;

    public JwtTokenService(
            @Value("${flashticket.security.jwt.secret:flashticket-super-secret-hmac-sha256-key-2026-production-grade}") String secretKey,
            @Value("${flashticket.security.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds) {
        this.secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public String generateAccessToken(String subject, List<String> roles, String scope) {
        return generateAccessTokenWithCustomTtl(subject, roles, scope, accessTokenTtlSeconds);
    }

    public String generateAccessTokenWithCustomTtl(
            String subject, List<String> roles, String scope, long ttlSeconds) {
        long nowEpochSeconds = Instant.now().getEpochSecond();
        long expEpochSeconds = nowEpochSeconds + ttlSeconds;
        String jti = UUID.randomUUID().toString();
        String rolesCsv = String.join(",", roles);

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = String.format(
                "{\"sub\":\"%s\",\"roles\":\"%s\",\"scope\":\"%s\",\"jti\":\"%s\",\"iat\":%d,\"exp\":%d}",
                escapeJson(subject),
                escapeJson(rolesCsv),
                escapeJson(scope != null ? scope : "read write"),
                jti,
                nowEpochSeconds,
                expEpochSeconds);

        String encodedHeader = BASE64_URL_ENCODER.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = BASE64_URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;
        String signature = signHmacSha256(signingInput);

        return signingInput + "." + signature;
    }

    public JwtClaims validateAndParseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new SecurityAuthenticationException("Token JWT ausente.");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new SecurityAuthenticationException("Formato de token JWT invalido (esperado Header.Payload.Signature).");
        }

        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = signHmacSha256(signingInput);

        // Comparação em tempo constante (evita Timing Attacks na verificação de assinatura!)
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityAuthenticationException("Assinatura criptografica do JWT invalida ou adulterada!");
        }

        String payloadJson;
        try {
            payloadJson = new String(BASE64_URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new SecurityAuthenticationException("Payload Base64Url do JWT corrompido.");
        }

        Map<String, String> rawClaims = parseSimpleJsonObject(payloadJson);
        long exp = Long.parseLong(rawClaims.getOrDefault("exp", "0"));
        long now = Instant.now().getEpochSecond();
        if (now >= exp) {
            throw new SecurityAuthenticationException("Token JWT expirado (exp=" + exp + "). Use o Refresh Token.");
        }

        long iat = Long.parseLong(rawClaims.getOrDefault("iat", "0"));
        String rolesCsv = rawClaims.getOrDefault("roles", "");
        List<String> roles = rolesCsv.isBlank()
                ? List.of()
                : Arrays.asList(rolesCsv.split(","));

        return new JwtClaims(
                rawClaims.get("sub"),
                roles,
                rawClaims.getOrDefault("scope", ""),
                rawClaims.get("jti"),
                iat,
                exp);
    }

    private String signHmacSha256(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKeyBytes, HMAC_SHA256));
            byte[] signatureBytes = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return BASE64_URL_ENCODER.encodeToString(signatureBytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Erro ao assinar JWT com HMAC-SHA256", ex);
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, String> parseSimpleJsonObject(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        for (String pair : trimmed.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx > 0) {
                String key = stripQuotes(pair.substring(0, colonIdx).trim());
                String val = stripQuotes(pair.substring(colonIdx + 1).trim());
                map.put(key, val);
            }
        }
        return map;
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public record JwtClaims(
            String subject,
            List<String> roles,
            String scope,
            String jti,
            long issuedAtEpochSeconds,
            long expiresAtEpochSeconds) {
    }
}
