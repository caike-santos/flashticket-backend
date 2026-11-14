package com.flashticket.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class AcidConcurrencyAndFlywayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Fase 2.1: Deve comprovar que o Flyway executou as migrations V1 e V2 e que o EXPLAIN usa o Indice Composto")
    void shouldVerifyFlywayMigrationsAndDatabaseIndexScan() throws Exception {
        // 1. Verifica se as migrations V1 e V2 foram aplicadas pelo Flyway
        mockMvc.perform(get("/api/v2/diagnostics/flyway"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("1"))
                .andExpect(jsonPath("$[1].version").value("2"));

        // 2. Verifica se o plano de execucao (EXPLAIN) usa o indice idx_events_category_event_date
        MvcResult explainResult = mockMvc.perform(get("/api/v2/diagnostics/query-plan")
                        .param("category", "CONCERT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexExpected").value("idx_events_category_event_date"))
                .andReturn();

        String responseBody = explainResult.getResponse().getContentAsString().toLowerCase();
        assertThat(responseBody).contains("idx_events_category_event_date");
    }

    @Test
    @DisplayName("Fase 2.2: Deve fazer ROLLBACK atomico (A do ACID) e devolver o estoque se ocorrer erro antes de finalizar o pedido")
    void shouldRollbackStockDeductionWhenTransactionFails() throws Exception {
        // 1. Cria um evento com 10 ingressos disponiveis
        String createEventPayload = """
                {
                  "title": "Workshop Transacoes ACID",
                  "description": "Teste de Atomicidade e Rollback",
                  "category": "WORKSHOP",
                  "eventDate": "2027-11-10T19:00:00",
                  "totalCapacity": 10,
                  "basePrice": 100.00
                }
                """;

        MvcResult eventResult = mockMvc.perform(post("/api/v2/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createEventPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String eventId = JsonPath.read(eventResult.getResponse().getContentAsString(), "$.id");

        // 2. Tenta comprar 4 ingressos simulando falha logo APOS abater o estoque no banco
        String purchaseWithFailurePayload = """
                {
                  "eventId": "%s",
                  "customerEmail": "cliente@teste.com",
                  "quantity": 4,
                  "usePessimisticLock": false,
                  "simulateFailureAfterStockDeduction": true
                }
                """.formatted(eventId);

        mockMvc.perform(post("/api/v2/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseWithFailurePayload))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Falha transacional (Rollback ACID acionado)"));

        // 3. Consulta o evento e comprova que o estoque continua intacto em 10 (Rollback funcionou!)
        mockMvc.perform(get("/api/v2/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTickets").value(10));
    }

    @Test
    @DisplayName("Fase 2.3: Deve impedir Overselling quando 10 clientes tentam comprar o ULTIMO ingresso simultaneamente")
    void shouldPreventOversellingUnderHighConcurrencyWithOptimisticLock() throws Exception {
        // 1. Cria um evento com APENAS 1 INGRESSO disponivel (totalCapacity = 1)
        String createEventPayload = """
                {
                  "title": "Final da Champions League - Ultimo Ingresso",
                  "description": "Disputa concorrente pelo ultimo lugar",
                  "category": "CONCERT",
                  "eventDate": "2027-05-30T16:00:00",
                  "totalCapacity": 1,
                  "basePrice": 500.00
                }
                """;

        MvcResult eventResult = mockMvc.perform(post("/api/v2/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createEventPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String eventId = JsonPath.read(eventResult.getResponse().getContentAsString(), "$.id");

        // 2. Dispara 10 threads exatamente no mesmo instante tentando comprar esse 1 ingresso
        int concurrentBuyers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentBuyers);
        CountDownLatch readyLatch = new CountDownLatch(concurrentBuyers);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 1; i <= concurrentBuyers; i++) {
            final int buyerNumber = i;
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(); // Todas as 10 threads largam juntas!

                String orderPayload = """
                        {
                          "eventId": "%s",
                          "customerEmail": "comprador%d@teste.com",
                          "quantity": 1,
                          "usePessimisticLock": false,
                          "simulateFailureAfterStockDeduction": false
                        }
                        """.formatted(eventId, buyerNumber);

                return mockMvc.perform(post("/api/v2/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(orderPayload))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });
        }

        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(executor.submit(task));
        }

        readyLatch.await();
        startLatch.countDown(); // Libera todas as 10 threads simultaneamente!

        int successCount201 = 0;
        int conflictCount409 = 0;
        for (Future<Integer> future : futures) {
            int httpStatus = future.get();
            if (httpStatus == 201) {
                successCount201++;
            } else if (httpStatus == 409) {
                conflictCount409++;
            }
        }
        executor.shutdown();

        // 3. EXATAMENTE 1 comprador deve conseguir (201 Created) e 9 devem receber 409 Conflict!
        assertThat(successCount201).isEqualTo(1);
        assertThat(conflictCount409).isEqualTo(9);

        // 4. O estoque final deve ser exatamente 0 (nunca negativo!)
        mockMvc.perform(get("/api/v2/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTickets").value(0));
    }
}
