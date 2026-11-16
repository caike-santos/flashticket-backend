package com.flashticket.application.service;

import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * TÓPICOS (Fase 7): Padrões de Resiliência em Integrações Externas:
 * 1. Timeouts (abortar chamadas lentas que excedem o limite em ms)
 * 2. Retries com Exponential Backoff + Jitter (apenas para falhas transitórias 503/Timeout; NUNCA para 4xx!)
 * 3. Circuit Breaker (estados CLOSED -> OPEN -> HALF_OPEN -> CLOSED)
 * 4. Bulkhead (isolamento de threads com Semaphore limitando chamadas simultâneas ao Gateway)
 * 5. Fallback (degradação graciosa: enfileira o pagamento para processamento posterior quando o Gateway cai)
 */
@Service
public class ResilientPaymentGatewayService {

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public enum SimulatedGatewayBehavior {
        HEALTHY_SUCCESS,
        TRANSIENT_FAIL_TWICE_THEN_SUCCEED,
        ALWAYS_FAIL_503,
        SLOW_TIMEOUT,
        NON_RETRYABLE_CARD_DECLINED_422
    }

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_DELAY_MS = 10L;
    private static final long GATEWAY_TIMEOUT_MS = 250L;
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final int BULKHEAD_MAX_CONCURRENT_CALLS = 2;

    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final Semaphore bulkheadSemaphore = new Semaphore(BULKHEAD_MAX_CONCURRENT_CALLS, true);
    private final ExecutorService timeoutExecutor = Executors.newCachedThreadPool();
    private final List<FallbackQueuedPayment> fallbackQueue = new CopyOnWriteArrayList<>();

    private final AuditLogRepositoryPort auditLogRepository;

    public ResilientPaymentGatewayService(AuditLogRepositoryPort auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public PaymentChargeResult chargeWithResilience(PaymentChargeCommand command) {
        List<String> executionTimeline = new ArrayList<>();

        // =====================================================================
        // PADRÃO 1: CIRCUIT BREAKER (Fail-Fast se o circuito estiver OPEN!)
        // =====================================================================
        if (circuitState == CircuitState.OPEN) {
            executionTimeline.add("CIRCUIT_BREAKER_OPEN: Chamada bloqueada em 0ms (Fail-Fast sem tocar no Gateway)");
            return executeFallback(command, "CIRCUIT_BREAKER_OPEN", 0, executionTimeline);
        }

        // =====================================================================
        // PADRÃO 2: BULKHEAD (Isolamento de Concorrência / Compartimento Estanque)
        // Impede que um Gateway lento consuma todas as threads da aplicação!
        // =====================================================================
        boolean acquiredPermit = bulkheadSemaphore.tryAcquire();
        if (!acquiredPermit) {
            executionTimeline.add("BULKHEAD_FULL: Limite de " + BULKHEAD_MAX_CONCURRENT_CALLS
                    + " chamadas simultaneas atingido! Rejeitando para proteger as threads do servidor.");
            return executeFallback(command, "BULKHEAD_CAPACITY_EXCEEDED", 0, executionTimeline);
        }

        int attempt = 0;
        try {
            // =================================================================
            // PADRÃO 3: RETRY COM EXPONENTIAL BACKOFF + JITTER
            // =================================================================
            while (attempt < MAX_RETRY_ATTEMPTS) {
                attempt++;
                try {
                    String gatewayTxId = executeSingleAttemptWithTimeout(command, attempt, executionTimeline);

                    // Sucesso! Se estava em HALF_OPEN ou tinha falhas acumuladas, fecha o circuito (CLOSED)
                    onGatewaySuccess(executionTimeline);

                    return new PaymentChargeResult(
                            command.orderId(),
                            "APPROVED",
                            gatewayTxId,
                            attempt,
                            circuitState,
                            false,
                            "Pagamento aprovado pelo Gateway na tentativa #" + attempt,
                            executionTimeline);

                } catch (NonRetryablePaymentException nonRetryableEx) {
                    // ERRO DE NEGÓCIO (422 Cartão sem saldo): NÃO faz Retry e NÃO abre o Circuit Breaker!
                    executionTimeline.add("Tentativa #" + attempt + " falhou com erro permanente (422): "
                            + nonRetryableEx.getMessage() + " -> Sem Retry!");
                    return new PaymentChargeResult(
                            command.orderId(),
                            "DECLINED_NON_RETRYABLE",
                            null,
                            attempt,
                            circuitState,
                            false,
                            nonRetryableEx.getMessage(),
                            executionTimeline);

                } catch (TransientGatewayException transientEx) {
                    executionTimeline.add("Tentativa #" + attempt + " falhou (" + transientEx.getMessage() + ")");

                    // Se o circuito estava em HALF_OPEN, qualquer falha já reabre o circuito imediatamente!
                    if (circuitState == CircuitState.HALF_OPEN) {
                        tripCircuitBreakerToOpen("Falha durante estado HALF_OPEN", executionTimeline);
                        return executeFallback(command, "HALF_OPEN_PROBE_FAILED", attempt, executionTimeline);
                    }

                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        long backoffWithJitterMs = computeExponentialBackoffWithJitter(attempt);
                        executionTimeline.add("Aguardando Exponential Backoff + Jitter ("
                                + backoffWithJitterMs + "ms) antes da tentativa #" + (attempt + 1));
                        sleepQuietly(backoffWithJitterMs);
                    } else {
                        // Esgotou todas as tentativas de Retry -> contabiliza falha no Circuit Breaker e aciona Fallback!
                        recordFailureTowardCircuitBreaker(executionTimeline);
                        return executeFallback(command, transientEx.getMessage(), attempt, executionTimeline);
                    }
                }
            }
            return executeFallback(command, "RETRIES_EXHAUSTED", attempt, executionTimeline);
        } finally {
            bulkheadSemaphore.release();
        }
    }

    /**
     * PADRÃO 4: TIMEOUT DE EXECUÇÃO
     * Aborta a chamada ao Gateway se ela ultrapassar GATEWAY_TIMEOUT_MS (120ms).
     */
    private String executeSingleAttemptWithTimeout(
            PaymentChargeCommand command,
            int attempt,
            List<String> executionTimeline) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            if (command.holdBulkheadSlotMs() > 0) {
                sleepQuietly(command.holdBulkheadSlotMs());
            }

            return switch (command.behavior()) {
                case HEALTHY_SUCCESS -> "gw_tx_" + UUID.randomUUID().toString().substring(0, 8);
                case TRANSIENT_FAIL_TWICE_THEN_SUCCEED -> {
                    if (attempt < 3) {
                        throw new TransientGatewayException("HTTP 503 Service Unavailable (Falha transitoria na tentativa #" + attempt + ")");
                    }
                    yield "gw_tx_recovered_" + UUID.randomUUID().toString().substring(0, 8);
                }
                case ALWAYS_FAIL_503 ->
                        throw new TransientGatewayException("HTTP 503 Gateway Down");
                case SLOW_TIMEOUT -> {
                    sleepQuietly(GATEWAY_TIMEOUT_MS + 180);
                    yield "gw_tx_too_late";
                }
                case NON_RETRYABLE_CARD_DECLINED_422 ->
                        throw new NonRetryablePaymentException("HTTP 422 Cartao recusado pelo emissor (saldo insuficiente)");
            };
        }, timeoutExecutor);

        try {
            String txId = future.get(GATEWAY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            executionTimeline.add("Tentativa #" + attempt + " concluida com sucesso dentro do Timeout (" + txId + ")");
            return txId;
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new TransientGatewayException("TIMEOUT: Gateway demorou mais que " + GATEWAY_TIMEOUT_MS + "ms");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeEx) {
                throw runtimeEx;
            }
            throw new TransientGatewayException("Erro inesperado no Gateway: " + cause.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TransientGatewayException("Thread interrompida aguardando Gateway");
        }
    }

    /**
     * Fórmula do Exponential Backoff + Jitter:
     * delay = baseDelay * 2^(attempt - 1) + random(0..5ms)
     */
    public long computeExponentialBackoffWithJitter(int attempt) {
        long exponential = BASE_BACKOFF_DELAY_MS * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(1, 6);
        return exponential + jitter;
    }

    /**
     * PADRÃO 5: FALLBACK (Degradação Graciosa)
     * Enfileira o pagamento para reprocessamento assíncrono posterior sem perder a venda!
     */
    private PaymentChargeResult executeFallback(
            PaymentChargeCommand command,
            String reason,
            int attemptsMade,
            List<String> executionTimeline) {
        executionTimeline.add("FALLBACK_ATIVADO: Pedido enfileirado para processamento posterior (Motivo: " + reason + ")");

        FallbackQueuedPayment queued = new FallbackQueuedPayment(
                UUID.randomUUID(),
                command.orderId(),
                command.amount(),
                reason,
                circuitState,
                Instant.now());
        fallbackQueue.add(queued);

        auditLogRepository.save(AuditLog.create(
                "PAYMENT_FALLBACK_QUEUED",
                command.orderId().toString(),
                "RESILIENCE_ENGINE",
                Map.of(
                        "reason", reason,
                        "attemptsMade", attemptsMade,
                        "circuitState", circuitState.name(),
                        "fallbackQueueSize", fallbackQueue.size()
                )
        ));

        return new PaymentChargeResult(
                command.orderId(),
                "FALLBACK_QUEUED_FOR_LATER_PROCESSING",
                queued.fallbackTicketId().toString(),
                attemptsMade,
                circuitState,
                true,
                "Gateway indisponivel no momento. Seu pagamento foi recebido em contingencia (Fallback) e sera processado em instantes!",
                executionTimeline);
    }

    private void onGatewaySuccess(List<String> timeline) {
        int previousFailures = consecutiveFailures.getAndSet(0);
        if (circuitState == CircuitState.HALF_OPEN || previousFailures > 0) {
            circuitState = CircuitState.CLOSED;
            timeline.add("CIRCUIT_BREAKER_CLOSED: Gateway respondeu com sucesso! Circuito fechado/normalizado.");
        }
    }

    private void recordFailureTowardCircuitBreaker(List<String> timeline) {
        int failures = consecutiveFailures.incrementAndGet();
        timeline.add("Falhas consecutivas registradas no Circuit Breaker: " + failures + "/" + CIRCUIT_FAILURE_THRESHOLD);
        if (failures >= CIRCUIT_FAILURE_THRESHOLD && circuitState == CircuitState.CLOSED) {
            tripCircuitBreakerToOpen("Atingiu " + failures + " falhas consecutivas", timeline);
        }
    }

    private void tripCircuitBreakerToOpen(String reason, List<String> timeline) {
        circuitState = CircuitState.OPEN;
        timeline.add("CIRCUIT_BREAKER_TRIPPED_TO_OPEN: Disjuntor ABERTO (" + reason + ")!");
    }

    public CircuitState transitionToHalfOpenForProbe() {
        this.circuitState = CircuitState.HALF_OPEN;
        return this.circuitState;
    }

    public void resetResilienceState() {
        this.circuitState = CircuitState.CLOSED;
        this.consecutiveFailures.set(0);
        this.fallbackQueue.clear();
    }

    public ResilienceDiagnosticsStatus getDiagnosticsStatus() {
        return new ResilienceDiagnosticsStatus(
                circuitState,
                consecutiveFailures.get(),
                CIRCUIT_FAILURE_THRESHOLD,
                bulkheadSemaphore.availablePermits(),
                BULKHEAD_MAX_CONCURRENT_CALLS,
                fallbackQueue.size(),
                new ArrayList<>(fallbackQueue));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public static class TransientGatewayException extends RuntimeException {
        public TransientGatewayException(String message) {
            super(message);
        }
    }

    public static class NonRetryablePaymentException extends RuntimeException {
        public NonRetryablePaymentException(String message) {
            super(message);
        }
    }

    public record PaymentChargeCommand(
            UUID orderId,
            BigDecimal amount,
            SimulatedGatewayBehavior behavior,
            long holdBulkheadSlotMs) {
    }

    public record PaymentChargeResult(
            UUID orderId,
            String status,
            String referenceId,
            int attemptsMade,
            CircuitState circuitStateAfterCall,
            boolean fallbackTriggered,
            String message,
            List<String> executionTimeline) {
    }

    public record FallbackQueuedPayment(
            UUID fallbackTicketId,
            UUID orderId,
            BigDecimal amount,
            String reason,
            CircuitState circuitStateWhenQueued,
            Instant queuedAt) {
    }

    public record ResilienceDiagnosticsStatus(
            CircuitState circuitState,
            int consecutiveFailures,
            int failureThreshold,
            int bulkheadAvailablePermits,
            int bulkheadMaxConcurrentCalls,
            int fallbackQueueSize,
            List<FallbackQueuedPayment> fallbackQueue) {
    }
}
