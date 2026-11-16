package com.flashticket.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashticket.application.service.ResilientPaymentGatewayService;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ResiliencePatternsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResilientPaymentGatewayService resilientGatewayService;

    @BeforeEach
    void resetCircuitBeforeEach() {
        resilientGatewayService.resetResilienceState();
    }

    @Test
    @DisplayName("Fase 7.1 & 7.2: Deve fazer Retry com Exponential Backoff em falhas transitórias (503), abortar por Timeout e NÃO fazer Retry em erros 422")
    void shouldRetryOnTransientFailuresTimeoutOnSlowCallsAndSkipRetryOn422() throws Exception {
        // 1. Falha transitória (falha nas tentativas #1 e #2 com 503 e passa na tentativa #3!)
        String transientRetryBody = """
                {
                  "amount": 300.00,
                  "behavior": "TRANSIENT_FAIL_TWICE_THEN_SUCCEED"
                }
                """;

        mockMvc.perform(post("/api/v2/resilience/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transientRetryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.attemptsMade").value(3))
                .andExpect(jsonPath("$.fallbackTriggered").value(false))
                .andExpect(jsonPath("$.circuitStateAfterCall").value("CLOSED"));

        // 2. Erro de negócio permanente (422 Cartão Recusado): NÃO deve fazer Retry (attemptsMade = 1)!
        String cardDeclinedBody = """
                {
                  "amount": 300.00,
                  "behavior": "NON_RETRYABLE_CARD_DECLINED_422"
                }
                """;

        mockMvc.perform(post("/api/v2/resilience/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardDeclinedBody))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value("DECLINED_NON_RETRYABLE"))
                .andExpect(jsonPath("$.attemptsMade").value(1))
                .andExpect(jsonPath("$.fallbackTriggered").value(false));

        // 3. Timeout: Gateway demora demais -> aborta nas 3 tentativas e aciona Fallback (202 Accepted)!
        String slowTimeoutBody = """
                {
                  "amount": 300.00,
                  "behavior": "SLOW_TIMEOUT"
                }
                """;

        mockMvc.perform(post("/api/v2/resilience/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slowTimeoutBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FALLBACK_QUEUED_FOR_LATER_PROCESSING"))
                .andExpect(jsonPath("$.attemptsMade").value(3))
                .andExpect(jsonPath("$.fallbackTriggered").value(true));
    }

    @Test
    @DisplayName("Fase 7.3: Deve abrir o Circuit Breaker (CLOSED -> OPEN) após 3 falhas, responder em Fail-Fast (0 tentativas) e fechar após sonda HALF_OPEN")
    void shouldTripCircuitBreakerToOpenFailFastAndRecoverViaHalfOpen() throws Exception {
        String alwaysFailBody = """
                {
                  "amount": 180.00,
                  "behavior": "ALWAYS_FAIL_503"
                }
                """;

        // 3 chamadas consecutivas que esgotam os Retries -> atingem o limite (failureThreshold = 3) e ABREM o disjuntor!
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/v2/resilience/charge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(alwaysFailBody))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.fallbackTriggered").value(true));
        }

        // Confirma que o Circuit Breaker agora está OPEN!
        mockMvc.perform(get("/api/v2/resilience/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitState").value("OPEN"))
                .andExpect(jsonPath("$.fallbackQueueSize").value(3));

        // Com o circuito OPEN, uma nova chamada falha em FAIL-FAST (attemptsMade = 0, nem toca no Gateway!)
        mockMvc.perform(post("/api/v2/resilience/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":180.00,\"behavior\":\"HEALTHY_SUCCESS\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptsMade").value(0))
                .andExpect(jsonPath("$.fallbackTriggered").value(true))
                .andExpect(jsonPath("$.circuitStateAfterCall").value("OPEN"));

        // Muda para HALF_OPEN (janela de teste de recuperação) e envia 1 chamada saudável -> volta para CLOSED!
        mockMvc.perform(post("/api/v2/resilience/circuit-breaker/transition-half-open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitState").value("HALF_OPEN"));

        mockMvc.perform(post("/api/v2/resilience/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":180.00,\"behavior\":\"HEALTHY_SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.circuitStateAfterCall").value("CLOSED"));
    }

    @Test
    @DisplayName("Fase 7.4: O Bulkhead deve limitar a 2 chamadas simultâneas ao Gateway e enviar a 3ª chamada concorrente direto para o Fallback")
    void shouldIsolateConcurrencyWithBulkheadAndTriggerFallbackWhenFull() throws Exception {
        // Ocupa as 2 únicas vagas do Bulkhead por 150ms
        CompletableFuture<ResilientPaymentGatewayService.PaymentChargeResult> slot1 =
                CompletableFuture.supplyAsync(() -> resilientGatewayService.chargeWithResilience(
                        new ResilientPaymentGatewayService.PaymentChargeCommand(
                                UUID.randomUUID(),
                                new BigDecimal("100.00"),
                                ResilientPaymentGatewayService.SimulatedGatewayBehavior.HEALTHY_SUCCESS,
                                150)));

        CompletableFuture<ResilientPaymentGatewayService.PaymentChargeResult> slot2 =
                CompletableFuture.supplyAsync(() -> resilientGatewayService.chargeWithResilience(
                        new ResilientPaymentGatewayService.PaymentChargeCommand(
                                UUID.randomUUID(),
                                new BigDecimal("100.00"),
                                ResilientPaymentGatewayService.SimulatedGatewayBehavior.HEALTHY_SUCCESS,
                                150)));

        // Aguarda até que ambas as vagas do Bulkhead estejam efetivamente ocupadas (0 vagas disponíveis)
        for (int i = 0; i < 40; i++) {
            if (resilientGatewayService.getDiagnosticsStatus().bulkheadAvailablePermits() == 0) {
                break;
            }
            Thread.sleep(5);
        }

        // 3ª chamada simultânea encontra o Bulkhead cheio (0 vagas livres) -> vai direto para o Fallback (0 tentativas)!
        ResilientPaymentGatewayService.PaymentChargeResult thirdCallResult =
                resilientGatewayService.chargeWithResilience(
                        new ResilientPaymentGatewayService.PaymentChargeCommand(
                                UUID.randomUUID(),
                                new BigDecimal("100.00"),
                                ResilientPaymentGatewayService.SimulatedGatewayBehavior.HEALTHY_SUCCESS,
                                0));

        assertThat(thirdCallResult.fallbackTriggered()).isTrue();
        assertThat(thirdCallResult.attemptsMade()).isZero();
        assertThat(thirdCallResult.executionTimeline().get(0)).contains("BULKHEAD_FULL");

        assertThat(slot1.get().status()).isEqualTo("APPROVED");
        assertThat(slot2.get().status()).isEqualTo("APPROVED");
    }
}
