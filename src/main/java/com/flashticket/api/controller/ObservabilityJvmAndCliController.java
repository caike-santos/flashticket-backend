package com.flashticket.api.controller;

import com.flashticket.application.service.JvmMemoryAndGcLabService;
import com.flashticket.infrastructure.cli.FlashTicketCliEngine;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TÓPICOS (Fase 8):
 * - Logs para Debug com SLF4J e MDC (Correlation-ID propagado automaticamente)
 * - Health Checks customizados (Liveness, Readiness do Gateway de Pagamento e Memória JVM)
 * - Gerenciamento de Memória (Stack vs Heap: Young/Old Gen, Metaspace) e Garbage Collection (G1GC)
 * - Simulação e Correção de Vazamento de Memória (Memory Leak em coleção estática GC Root)
 * - Execução de comandos da CLI Picocli ('flashticket stats', 'flashticket health', 'flashticket cancel-expired', 'flashticket gc')
 */
@RestController
@RequestMapping("/api/v2/observability")
public class ObservabilityJvmAndCliController {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityJvmAndCliController.class);

    private final JvmMemoryAndGcLabService jvmMemoryService;
    private final FlashTicketCliEngine cliEngine;

    public ObservabilityJvmAndCliController(
            JvmMemoryAndGcLabService jvmMemoryService,
            FlashTicketCliEngine cliEngine) {
        this.jvmMemoryService = jvmMemoryService;
        this.cliEngine = cliEngine;
    }

    @GetMapping("/logs-mdc-demo")
    public ResponseEntity<Map<String, Object>> demonstrateStructuredLogsAndMdc() {
        String correlationId = MDC.get("correlationId");
        log.debug("[DEBUG] Detalhe tecnico interno para desenvolvedores (correlationId={})", correlationId);
        log.info("[INFO] Evento de negocio normal rastreado pelo MDC Correlation-ID");
        log.warn("[WARN] Alerta preventivo (ex: latencia acima da media ou tentativa de retry)");

        return ResponseEntity.ok(Map.of(
                "correlationIdFromMdc", correlationId != null ? correlationId : "NONE",
                "logLevelsDemonstrated", List.of("DEBUG", "INFO", "WARN", "ERROR"),
                "explanation", "Todas as linhas de log desta requisicao sairam no console com [corrId="
                        + correlationId + "] automaticamente via SLF4J MDC!"
        ));
    }

    @GetMapping("/health-custom")
    public ResponseEntity<JvmMemoryAndGcLabService.CustomPlatformHealthReport> getCustomHealthReport() {
        return ResponseEntity.ok(jvmMemoryService.evaluateCustomHealth());
    }

    @GetMapping("/jvm-memory")
    public ResponseEntity<JvmMemoryAndGcLabService.JvmMemoryAndGcSnapshot> inspectJvmMemoryAndGc() {
        return ResponseEntity.ok(jvmMemoryService.inspectJvmMemoryAndGc());
    }

    @PostMapping("/memory-leak/simulate")
    public ResponseEntity<JvmMemoryAndGcLabService.MemoryLeakSimulationResult> simulateMemoryLeak(
            @RequestParam(defaultValue = "5") int megabytes) {
        return ResponseEntity.ok(jvmMemoryService.simulateMemoryLeak(megabytes));
    }

    @PostMapping("/memory-leak/fix-and-gc")
    public ResponseEntity<JvmMemoryAndGcLabService.MemoryLeakSimulationResult> fixMemoryLeakAndRunGc() {
        return ResponseEntity.ok(jvmMemoryService.fixMemoryLeakAndTriggerGc());
    }

    @PostMapping("/cli")
    public ResponseEntity<FlashTicketCliEngine.CliExecutionResult> executeCliCommand(
            @RequestBody CliCommandRequest request) {
        String[] args = (request.args() == null || request.args().isEmpty())
                ? new String[]{"--help"}
                : request.args().toArray(new String[0]);
        return ResponseEntity.ok(cliEngine.executeCommand(args));
    }

    public record CliCommandRequest(List<String> args) {
    }
}
