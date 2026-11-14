package com.flashticket.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashticket.application.service.EventService;
import com.flashticket.application.service.TicketPurchaseService;
import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import com.flashticket.infrastructure.persistence.jpa.HibernateSqlCounter;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
class GraphqlNPlusOneIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventService eventService;

    @Autowired
    private TicketPurchaseService ticketPurchaseService;

    @Test
    @DisplayName("Fase 3: Deve comprovar que eventsWithNPlusOneProblem executa N+1 SELECTs e eventsOptimized (@BatchMapping) executa apenas 2 SELECTs")
    void shouldDemonstrateNPlusOneProblemAndSolveWithBatchMapping() throws Exception {
        // 1. Garante que existam pelo menos 4 eventos com pedidos no banco
        for (int i = 1; i <= 4; i++) {
            Event event = eventService.createEvent(
                    "Evento GraphQL #" + i,
                    "Teste de N+1 Queries no GraphQL",
                    EventCategory.CONFERENCE,
                    LocalDateTime.now().plusDays(10 + i),
                    100,
                    new BigDecimal("150.00")
            );
            ticketPurchaseService.purchaseTickets(
                    event.getId(),
                    "participante" + i + "@flashticket.com",
                    2,
                    false,
                    false
            );
        }

        // =====================================================================
        // CENÁRIO A: ❌ EXECUTANDO A QUERY GRAPHQL COM PROBLEMA DE N+1
        // =====================================================================
        HibernateSqlCounter.reset();

        String nPlusOneGraphqlQuery = """
                {
                  "query": "query { eventsWithNPlusOneProblem { id title availableTickets orders { id customerEmail quantity totalAmount status } } }"
                }
                """;

        MvcResult nPlusOneResult = mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nPlusOneGraphqlQuery))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventsWithNPlusOneProblem").isArray())
                .andReturn();

        List<?> eventsReturned = JsonPath.read(
                nPlusOneResult.getResponse().getContentAsString(),
                "$.data.eventsWithNPlusOneProblem");
        int totalEventsN = eventsReturned.size();
        int queriesExecutedInNPlusOne = HibernateSqlCounter.getSelectCount();

        // Comprova matematicamente o N+1: 1 SELECT para eventos + N SELECTs para os pedidos de cada evento!
        assertThat(totalEventsN).isGreaterThanOrEqualTo(4);
        assertThat(queriesExecutedInNPlusOne).isEqualTo(totalEventsN + 1);

        // =====================================================================
        // CENÁRIO B: ✅ EXECUTANDO A QUERY GRAPHQL OTIMIZADA COM @BatchMapping
        // =====================================================================
        HibernateSqlCounter.reset();

        String optimizedGraphqlQuery = """
                {
                  "query": "query { eventsOptimized { id title category availableTickets finalPrice orders { id customerEmail quantity totalAmount status } } }"
                }
                """;

        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(optimizedGraphqlQuery))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventsOptimized").isArray())
                .andExpect(jsonPath("$.data.eventsOptimized.length()").value(totalEventsN));

        int queriesExecutedInBatch = HibernateSqlCounter.getSelectCount();

        // Comprova a solução: não importa quantos N eventos existam, executa SEMPRE APENAS 2 SELECTs!
        // (1 SELECT na tabela events + 1 SELECT ... WHERE event_id IN (...) na tabela ticket_orders)
        assertThat(queriesExecutedInBatch).isEqualTo(2);
    }
}
