package com.flashticket.api.controller;

import com.flashticket.application.service.ResilientPaymentGatewayService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TÓPICOS (Fase 7): Padrões de Resiliência em Integrações Externas:
 * - Timeouts (Connect Timeout, Read Timeout e Execution Timeout)
 * - Retries com Exponential Backoff + Jitter (apenas para erros transitórios 503/Timeout)
 * - Circuit Breaker (CLOSED -> OPEN -> HALF_OPEN -> CLOSED)
 * - Bulkhead (isolamento de threads concorrentes com Semaphore)
 * - Fallback (enfileiramento de contingência quando o Gateway externo está fora do ar)
 */
@RestController
@RequestMapping("/api/v2/resilience")
public class ResilienceController {

    private final ResilientPaymentGatewayService resilientGatewayService;

    public ResilienceController(ResilientPaymentGatewayService resilientGatewayService) {
        this.resilientGatewayService = resilientGatewayService;
    }

    @PostMapping("/charge")
    public ResponseEntity<ResilientPaymentGatewayService.PaymentChargeResult> chargeOrderWithResilience(
            @RequestBody ChargeRequest request) {
        UUID orderId = request.orderId() != null ? request.orderId() : UUID.randomUUID();
        BigDecimal amount = request.amount() != null ? request.amount() : new BigDecimal("250.00");
        ResilientPaymentGatewayService.SimulatedGatewayBehavior behavior =
                request.behavior() != null
                        ? request.behavior()
                        : ResilientPaymentGatewayService.SimulatedGatewayBehavior.HEALTHY_SUCCESS;

        long holdMs = request.holdBulkheadSlotMs() != null ? request.holdBulkheadSlotMs() : 0L;

        ResilientPaymentGatewayService.PaymentChargeResult result =
                resilientGatewayService.chargeWithResilience(
                        new ResilientPaymentGatewayService.PaymentChargeCommand(
                                orderId,
                                amount,
                                behavior,
                                holdMs));

        if ("DECLINED_NON_RETRYABLE".equals(result.status())) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(result);
        }
        if (result.fallbackTriggered()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<ResilientPaymentGatewayService.ResilienceDiagnosticsStatus> getResilienceStatus() {
        return ResponseEntity.ok(resilientGatewayService.getDiagnosticsStatus());
    }

    @PostMapping("/circuit-breaker/transition-half-open")
    public ResponseEntity<Map<String, String>> transitionToHalfOpen() {
        ResilientPaymentGatewayService.CircuitState newState =
                resilientGatewayService.transitionToHalfOpenForProbe();
        return ResponseEntity.ok(Map.of(
                "circuitState", newState.name(),
                "message", "Circuit Breaker movido para HALF_OPEN! A proxima chamada servira como teste de recuperacao do Gateway."
        ));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetResilienceState() {
        resilientGatewayService.resetResilienceState();
        return ResponseEntity.ok(Map.of(
                "message", "Circuit Breaker fechado (CLOSED), contadores zerados e fila de Fallback limpa!"
        ));
    }

    public record ChargeRequest(
            UUID orderId,
            BigDecimal amount,
            ResilientPaymentGatewayService.SimulatedGatewayBehavior behavior,
            Long holdBulkheadSlotMs) {
    }
}
