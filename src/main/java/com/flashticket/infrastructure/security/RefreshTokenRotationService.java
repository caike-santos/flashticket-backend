package com.flashticket.infrastructure.security;

import com.flashticket.domain.exception.SecurityAuthenticationException;
import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * TÓPICO (Fase 5): Rotação de JWT (Refresh Token Rotation com Detecção de Reuso / Roubo).
 *
 * Como funciona?
 * 1. O Access Token (JWT) dura apenas 15 minutos. Junto com ele, emitimos um Refresh Token
 *    de uso único pertencente a uma "Família de Tokens" (familyId).
 * 2. Quando o cliente chama POST /api/v2/auth/refresh enviando o Refresh Token #1:
 *    - O servidor marca o Refresh Token #1 como USADO (used = true).
 *    - Emite um novo Access Token + um novo Refresh Token #2 na mesma família (familyId).
 * 3. DETECÇÃO DE ROUBO (Reuse Detection):
 *    - Se um invasor roubou o Refresh Token #1 e tentar usá-lo (ou se o invasor usou primeiro
 *      e o usuário legítimo tentar usar o #1 depois), o servidor percebe que o Token #1 JÁ FOI USADO!
 *    - Imediatamente, o servidor REVOGA TODA A FAMÍLIA (familyId), invalidando inclusive o Token #2,
 *      grava um alerta crítico na Trilha de Auditoria (NoSQL) e força um novo login!
 */
@Service
public class RefreshTokenRotationService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRotationService.class);

    private final Map<String, RefreshTokenRecord> tokenStore = new ConcurrentHashMap<>();
    private final JwtTokenService jwtTokenService;
    private final AuditLogRepositoryPort auditLogRepository;

    public RefreshTokenRotationService(
            JwtTokenService jwtTokenService,
            AuditLogRepositoryPort auditLogRepository) {
        this.jwtTokenService = jwtTokenService;
        this.auditLogRepository = auditLogRepository;
    }

    public TokenPair issueInitialTokenPair(String subject, List<String> roles, String scope) {
        String familyId = UUID.randomUUID().toString();
        return createTokenPairForFamily(familyId, subject, roles, scope);
    }

    public TokenPair rotateRefreshToken(String rawRefreshToken) {
        RefreshTokenRecord existing = tokenStore.get(rawRefreshToken);
        if (existing == null) {
            throw new SecurityAuthenticationException("Refresh Token inexistente ou invalido.");
        }

        if (existing.revoked()) {
            throw new SecurityAuthenticationException("Refresh Token foi revogado por seguranca.");
        }

        if (Instant.now().isAfter(existing.expiresAt())) {
            throw new SecurityAuthenticationException("Refresh Token expirado. Realize login novamente.");
        }

        // =========================================================================
        // DETECÇÃO DE REUSO (REUSE DETECTION): O Refresh Token já havia sido usado!
        // Isso indica tentativa de Replay Attack ou roubo do Refresh Token.
        // Solução: Revogar imediatamente TODOS os tokens da mesma família (familyId)!
        // =========================================================================
        if (existing.used()) {
            log.error("ALERTA DE SEGURANCA: Reuso de Refresh Token detectado! Revogando familia={} (subject={})",
                    existing.familyId(), existing.subject());
            revokeEntireFamily(existing.familyId(), existing.subject(), rawRefreshToken);
            throw new SecurityAuthenticationException(
                    "ALERTA DE SEGURANCA: Reuso de Refresh Token detectado! Toda a familia de tokens ("
                            + existing.familyId() + ") foi revogada preventivamente.");
        }

        // Marca o Refresh Token atual como usado (uso único!)
        tokenStore.put(rawRefreshToken, existing.markAsUsed());

        // Emite um novo par (Access Token + novo Refresh Token na mesma família)
        return createTokenPairForFamily(
                existing.familyId(),
                existing.subject(),
                existing.roles(),
                existing.scope());
    }

    private TokenPair createTokenPairForFamily(
            String familyId, String subject, List<String> roles, String scope) {
        String accessToken = jwtTokenService.generateAccessToken(subject, roles, scope);
        String newRefreshToken = "rt_" + UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        RefreshTokenRecord record = new RefreshTokenRecord(
                newRefreshToken,
                familyId,
                subject,
                roles,
                scope,
                false,
                false,
                expiresAt);
        tokenStore.put(newRefreshToken, record);

        return new TokenPair(accessToken, newRefreshToken, familyId, "Bearer", 900);
    }

    private void revokeEntireFamily(String familyId, String subject, String compromisedToken) {
        int revokedCount = 0;
        for (Map.Entry<String, RefreshTokenRecord> entry : tokenStore.entrySet()) {
            RefreshTokenRecord rec = entry.getValue();
            if (rec.familyId().equals(familyId) && !rec.revoked()) {
                tokenStore.put(entry.getKey(), rec.markAsRevoked());
                revokedCount++;
            }
        }

        auditLogRepository.save(AuditLog.create(
                "SECURITY_REFRESH_TOKEN_REUSE_DETECTED",
                familyId,
                subject,
                Map.of(
                        "compromisedRefreshToken", compromisedToken,
                        "tokenFamilyId", familyId,
                        "revokedTokensInChain", revokedCount,
                        "actionTaken", "REVOKED_ENTIRE_TOKEN_FAMILY"
                )
        ));
    }

    public record RefreshTokenRecord(
            String token,
            String familyId,
            String subject,
            List<String> roles,
            String scope,
            boolean used,
            boolean revoked,
            Instant expiresAt) {

        public RefreshTokenRecord markAsUsed() {
            return new RefreshTokenRecord(token, familyId, subject, roles, scope, true, revoked, expiresAt);
        }

        public RefreshTokenRecord markAsRevoked() {
            return new RefreshTokenRecord(token, familyId, subject, roles, scope, used, true, expiresAt);
        }
    }

    public record TokenPair(
            String accessToken,
            String refreshToken,
            String tokenFamilyId,
            String tokenType,
            long expiresInSeconds) {
    }
}
