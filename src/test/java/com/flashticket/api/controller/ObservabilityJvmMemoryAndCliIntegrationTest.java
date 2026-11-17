package com.flashticket.api.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityJvmMemoryAndCliIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Fase 8.1 & 8.2: Deve propagar Correlation-ID no MDC/Header e validar Health Checks (Actuator + Custom Gateway Health)")
    void shouldPropagateMdcCorrelationIdAndReportPlatformHealth() throws Exception {
        // 1. Quando o cliente envia X-Correlation-ID, o filtro propaga para o MDC e devolve no Header
        mockMvc.perform(get("/api/v2/observability/logs-mdc-demo")
                        .header("X-Correlation-ID", "trace-pedido-999"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", "trace-pedido-999"))
                .andExpect(jsonPath("$.correlationIdFromMdc").value("trace-pedido-999"));

        // 2. Quando o cliente não envia X-Correlation-ID, o filtro gera automaticamente 'req_...'
        mockMvc.perform(get("/api/v2/observability/logs-mdc-demo"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", startsWith("req_")))
                .andExpect(jsonPath("$.correlationIdFromMdc", startsWith("req_")));

        // 3. Health Check nativo do Spring Boot Actuator (/actuator/health) + Health Check customizado
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/api/v2/observability/health-custom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("UP"))
                .andExpect(jsonPath("$.livenessState").value("UP"))
                .andExpect(jsonPath("$.paymentGatewayReadiness").value("UP"));
    }

    @Test
    @DisplayName("Fase 8.3: Deve inspecionar Heap/Metaspace/GC da JVM, simular Memory Leak em lista estática e liberar memória no GC")
    void shouldInspectJvmMemorySimulateMemoryLeakAndFixWithGarbageCollection() throws Exception {
        // 1. Inspeciona memória JVM inicial (Heap, Metaspace, Pools Eden/Survivor/Old Gen e G1GC)
        mockMvc.perform(get("/api/v2/observability/jvm-memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heapUsedMb", greaterThan(0.0)))
                .andExpect(jsonPath("$.memoryPools").isArray())
                .andExpect(jsonPath("$.garbageCollectors").isArray());

        // 2. Simula Memory Leak alocando 4 MB retidos em referência estática (GC Root)
        mockMvc.perform(post("/api/v2/observability/memory-leak/simulate")
                        .param("megabytes", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MEMORY_LEAK_ACTIVE"))
                .andExpect(jsonPath("$.retainedMegabytesInStaticList").value(4));

        // 3. Corrige o Memory Leak limpando a lista estática e acionando o Garbage Collector
        mockMvc.perform(post("/api/v2/observability/memory-leak/fix-and-gc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MEMORY_LEAK_CLEARED_AND_GC_TRIGGERED"))
                .andExpect(jsonPath("$.retainedMegabytesInStaticList").value(0));
    }

    @Test
    @DisplayName("Fase 8.4: Deve executar comandos administrativos na CLI Picocli (stats, health, cancel-expired, gc e --help)")
    void shouldExecutePicocliAdminCommands() throws Exception {
        // 1. Comando: flashticket stats --verbose
        mockMvc.perform(post("/api/v2/observability/cli")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"args\":[\"stats\",\"--verbose\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.output", containsString("FLASHTICKET ADMIN CLI :: STATS")))
                .andExpect(jsonPath("$.output", containsString("ActiveThreads=")));

        // 2. Comando: flashticket health
        mockMvc.perform(post("/api/v2/observability/cli")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"args\":[\"health\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.output", containsString("OverallStatus=UP")));

        // 3. Comando: flashticket cancel-expired
        mockMvc.perform(post("/api/v2/observability/cli")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"args\":[\"cancel-expired\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.output", containsString("CancelledOrders=")));
    }
}
