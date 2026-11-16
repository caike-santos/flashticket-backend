package com.flashticket.infrastructure.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TÓPICO (Fase 5): Rate Limiter (Algoritmo Token Bucket).
 *
 * Como funciona o algoritmo Token Bucket?
 * - Cada cliente (identificado por IP ou API Key) possui um "balde" com capacidade máxima
 *   de N fichas (ex: 5 fichas por janela de 60 segundos).
 * - Cada requisição consome 1 ficha do balde.
 * - Enquanto houver fichas (remaining >= 0), a requisição passa (200 OK) com o header
 *   'X-Rate-Limit-Remaining'.
 * - Quando o balde esvazia, o servidor bloqueia imediatamente com HTTP 429 (Too Many Requests)
 *   e informa em quantos segundos o balde será reabastecido ('X-Rate-Limit-Retry-After-Seconds').
 *
 * Protege a API contra ataques de Força Bruta (no /login), DDoS de camada 7,
 * bots cambistas comprando ingressos e scraping abusivo.
 */
@Service
public class RateLimitService {

    private final Map<String, TokenBucketState> buckets = new ConcurrentHashMap<>();
    private final int maxRequestsPerWindow;
    private final long windowSeconds;

    public RateLimitService(
            @Value("${flashticket.security.rate-limit.max-requests:5}") int maxRequestsPerWindow,
            @Value("${flashticket.security.rate-limit.window-seconds:60}") long windowSeconds) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowSeconds = windowSeconds;
    }

    public RateLimitDecision tryConsume(String clientKey) {
        Instant now = Instant.now();
        TokenBucketState updatedState = buckets.compute(clientKey, (key, current) -> {
            if (current == null || !now.isBefore(current.windowResetAt())) {
                return new TokenBucketState(maxRequestsPerWindow - 1, now.plusSeconds(windowSeconds), true);
            }
            if (current.remainingTokens() > 0) {
                return new TokenBucketState(current.remainingTokens() - 1, current.windowResetAt(), true);
            }
            return new TokenBucketState(0, current.windowResetAt(), false);
        });

        long retryAfterSeconds = Math.max(1, updatedState.windowResetAt().getEpochSecond() - now.getEpochSecond());
        return new RateLimitDecision(
                updatedState.lastConsumeAllowed(),
                updatedState.remainingTokens(),
                maxRequestsPerWindow,
                retryAfterSeconds);
    }

    public void resetAllBuckets() {
        buckets.clear();
    }

    private record TokenBucketState(
            int remainingTokens,
            Instant windowResetAt,
            boolean lastConsumeAllowed) {
    }

    public record RateLimitDecision(
            boolean allowed,
            int remainingTokens,
            int limitCapacity,
            long retryAfterSeconds) {
    }
}
