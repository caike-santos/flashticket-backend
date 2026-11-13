package com.flashticket.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Deve criar evento na v2 aplicando Strategy de taxa (SOLID) e consultar contratos v1, v2 e via Header")
    void shouldCreateEventAndDemonstrateApiVersioningAndSolidFeeCalculation() throws Exception {
        String payload = """
                {
                  "title": "Rock in Rio - Noite do Metal",
                  "description": "Show principal no Palco Mundo",
                  "category": "CONCERT",
                  "eventDate": "2027-09-15T20:00:00",
                  "totalCapacity": 500,
                  "basePrice": 200.00
                }
                """;

        // 1. POST /api/v2/events -> 201 Created + Location + Taxa de 15% para CONCERT (R$ 30,00)
        MvcResult createResult = mockMvc.perform(post("/api/v2/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.pricing.basePrice").value(200.00))
                .andExpect(jsonPath("$.pricing.platformFee").value(30.00))
                .andExpect(jsonPath("$.pricing.finalPrice").value(230.00))
                .andExpect(jsonPath("$.pricing.currency").value("BRL"))
                .andReturn();

        String id = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

        // 2. GET /api/v1/events/{id} -> Contrato legado (campo 'price' direto) + Header Deprecation
        mockMvc.perform(get("/api/v1/events/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(jsonPath("$.price").value(230.00))
                .andExpect(jsonPath("$.pricing").doesNotExist());

        // 3. GET /api/events/{id} com Header X-API-Version=2 -> Contrato v2
        mockMvc.perform(get("/api/events/" + id)
                        .header("X-API-Version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing.finalPrice").value(230.00));

        // 4. DELETE /api/v2/events/{id} -> 204 No Content e depois 404 Not Found (ProblemDetail RFC 7807)
        mockMvc.perform(delete("/api/v2/events/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v2/events/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso nao encontrado"));
    }

    @Test
    @DisplayName("Deve retornar 422 Unprocessable Entity (ProblemDetail) quando dados forem invalidos")
    void shouldReturn422WhenRequestIsInvalid() throws Exception {
        String invalidPayload = """
                {
                  "title": "",
                  "description": "",
                  "category": "WORKSHOP",
                  "eventDate": "2027-10-10T10:00:00",
                  "totalCapacity": 0,
                  "basePrice": -10.00
                }
                """;

        mockMvc.perform(post("/api/v2/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Erro de validacao de dados"))
                .andExpect(jsonPath("$.fields.title").exists())
                .andExpect(jsonPath("$.fields.totalCapacity").exists());
    }
}
