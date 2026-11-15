package com.flashticket.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashticket.infrastructure.persistence.jpa.HibernateSqlCounter;
import com.jayway.jsonpath.JsonPath;
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
class NoSqlAuditAndCacheIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Fase 4: Deve comprovar Cache-Aside (@Cacheable Hit/Miss + @CacheEvict na compra) e Documentos NoSQL de Auditoria com Schema Flexivel")
    void shouldDemonstrateCacheAsideHitMissEvictAndNoSqlFlexibleAuditLogs() throws Exception {
        // 1. Cria um novo evento (capacidade = 50)
        String eventPayload = """
                {
                  "title": "Show Cache Redis & NoSQL Mongo",
                  "description": "Demonstracao de Cache-Aside e Documentos NoSQL",
                  "category": "CONCERT",
                  "eventDate": "2027-12-01T21:00:00",
                  "totalCapacity": 50,
                  "basePrice": 100.00
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v2/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String eventId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        // =====================================================================
        // PARTE 1: COMPROVANDO CACHE MISS (1ª leitura) vs CACHE HIT (2ª e 3ª leituras)
        // =====================================================================
        HibernateSqlCounter.reset();

        // 1ª leitura: CACHE MISS -> precisa ir ao banco relacional (1 SELECT)
        mockMvc.perform(get("/api/v2/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTickets").value(50));

        assertThat(HibernateSqlCounter.getSelectCount()).isEqualTo(1);

        // 2ª e 3ª leituras seguidas: CACHE HIT -> retorna direto da memória RAM (0 novos SELECTs!)
        mockMvc.perform(get("/api/v2/events/" + eventId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v2/events/" + eventId)).andExpect(status().isOk());

        assertThat(HibernateSqlCounter.getSelectCount()).isEqualTo(1); // Continua 1! Não bateu no banco!

        // =====================================================================
        // PARTE 2: COMPROVANDO INVALIDAÇÃO DE CACHE (@CacheEvict) AO COMPRAR INGRESSO
        // =====================================================================
        String purchasePayload = """
                {
                  "eventId": "%s",
                  "customerEmail": "comprador.cache@flashticket.com",
                  "quantity": 3,
                  "usePessimisticLock": false,
                  "simulateFailureAfterStockDeduction": false
                }
                """.formatted(eventId);

        mockMvc.perform(post("/api/v2/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchasePayload))
                .andExpect(status().isCreated());

        // Agora o cache de #eventId foi invalidado pelo @CacheEvict!
        // Na próxima leitura deve ir ao banco (1 novo SELECT) e retornar availableTickets = 47!
        HibernateSqlCounter.reset();

        mockMvc.perform(get("/api/v2/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTickets").value(47));

        assertThat(HibernateSqlCounter.getSelectCount()).isEqualTo(1);

        // =====================================================================
        // PARTE 3: COMPROVANDO DOCUMENTOS NOSQL (AuditLog) COM SCHEMA FLEXÍVEL
        // =====================================================================
        mockMvc.perform(get("/api/v2/audit-logs")
                        .param("resourceId", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Documento mais recente: TICKET_PURCHASED (possui orderId, quantity, lockStrategy)
                .andExpect(jsonPath("$[0].actionType").value("TICKET_PURCHASED"))
                .andExpect(jsonPath("$[0].flexiblePayload.quantity").value(3))
                .andExpect(jsonPath("$[0].flexiblePayload.remainingTickets").value(47))
                .andExpect(jsonPath("$[0].flexiblePayload.lockStrategy").value("OPTIMISTIC_VERSION"))
                // Documento anterior: EVENT_CREATED (possui title, category, finalPrice)
                .andExpect(jsonPath("$[1].actionType").value("EVENT_CREATED"))
                .andExpect(jsonPath("$[1].flexiblePayload.title").value("Show Cache Redis & NoSQL Mongo"))
                .andExpect(jsonPath("$[1].flexiblePayload.finalPrice").value(115.00));
    }
}
