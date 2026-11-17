package com.flashticket.infrastructure.cli;

import com.flashticket.application.service.EventService;
import com.flashticket.application.service.ExpiredOrderCleanupScheduler;
import com.flashticket.application.service.JvmMemoryAndGcLabService;
import com.flashticket.application.service.ResilientPaymentGatewayService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * TÓPICO (Fase 8): CLI (Command-Line Interface) Administrativa com Picocli.
 *
 * Por que construir uma CLI administrativa no Backend?
 * - Permite que engenheiros DevOps/SRE e scripts de automação operem a plataforma via terminal
 *   (ex: 'flashticket stats', 'flashticket cancel-expired', 'flashticket health', 'flashticket gc')
 *   com parsing automático de argumentos, flags (--verbose) e tela de ajuda (--help).
 */
@Component
public class FlashTicketCliEngine {

    private final EventService eventService;
    private final ExpiredOrderCleanupScheduler expiredOrderCleanupScheduler;
    private final JvmMemoryAndGcLabService jvmMemoryService;
    private final ResilientPaymentGatewayService resilientGatewayService;

    public FlashTicketCliEngine(
            EventService eventService,
            ExpiredOrderCleanupScheduler expiredOrderCleanupScheduler,
            JvmMemoryAndGcLabService jvmMemoryService,
            ResilientPaymentGatewayService resilientGatewayService) {
        this.eventService = eventService;
        this.expiredOrderCleanupScheduler = expiredOrderCleanupScheduler;
        this.jvmMemoryService = jvmMemoryService;
        this.resilientGatewayService = resilientGatewayService;
    }

    public CliExecutionResult executeCommand(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        RootCliCommand root = new RootCliCommand();
        CommandLine cmd = new CommandLine(root)
                .addSubcommand("stats", new StatsCommand())
                .addSubcommand("cancel-expired", new CancelExpiredCommand())
                .addSubcommand("health", new HealthCommand())
                .addSubcommand("gc", new GcCommand());

        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute(args);
        String combinedOutput = out.toString() + (err.toString().isBlank() ? "" : "\n" + err);
        return new CliExecutionResult(
                "flashticket " + String.join(" ", args),
                exitCode,
                combinedOutput.trim());
    }

    @Command(
            name = "flashticket",
            mixinStandardHelpOptions = true,
            version = "FlashTicket CLI 0.9.0",
            description = "CLI Administrativa da Plataforma FlashTicket (Picocli)")
    public static class RootCliCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            return 0;
        }
    }

    @Command(name = "stats", mixinStandardHelpOptions = true, description = "Exibe estatisticas de eventos, resiliencia e memoria JVM")
    public class StatsCommand implements Callable<Integer> {

        @Option(names = {"-v", "--verbose"}, description = "Exibe detalhes completos de memória e pools")
        private boolean verbose;

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            int totalEvents = eventService.listEvents(null).size();
            var resilience = resilientGatewayService.getDiagnosticsStatus();
            var memory = jvmMemoryService.inspectJvmMemoryAndGc();

            PrintWriter writer = spec.commandLine().getOut();
            writer.println("=== FLASHTICKET ADMIN CLI :: STATS ===");
            writer.println("TotalEvents=" + totalEvents);
            writer.println("CircuitBreakerState=" + resilience.circuitState());
            writer.println("FallbackQueueSize=" + resilience.fallbackQueueSize());
            writer.println("HeapUsedMB=" + memory.heapUsedMb() + " / " + memory.heapMaxMb());
            writer.println("LeakedMB=" + memory.leakedMegabytesRetained());
            if (verbose) {
                writer.println("ActiveThreads=" + memory.activeThreadsStackCount());
                writer.println("MetaspaceUsedMB=" + memory.metaspaceAndNonHeapUsedMb());
            }
            return 0;
        }
    }

    @Command(name = "cancel-expired", mixinStandardHelpOptions = true, description = "Executa limpeza imediata de pedidos PENDING expirados")
    public class CancelExpiredCommand implements Callable<Integer> {

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var report = expiredOrderCleanupScheduler.cancelExpiredPendingOrders();
            PrintWriter writer = spec.commandLine().getOut();
            writer.println("=== FLASHTICKET ADMIN CLI :: CANCEL-EXPIRED ===");
            writer.println("CancelledOrders=" + report.cancelledOrdersCount());
            writer.println("TicketsRestoredToStock=" + report.totalTicketsRestoredToStock());
            return 0;
        }
    }

    @Command(name = "health", mixinStandardHelpOptions = true, description = "Verifica Liveness, Readiness do Gateway e saude da Heap JVM")
    public class HealthCommand implements Callable<Integer> {

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var health = jvmMemoryService.evaluateCustomHealth();
            PrintWriter writer = spec.commandLine().getOut();
            writer.println("=== FLASHTICKET ADMIN CLI :: HEALTH ===");
            writer.println("OverallStatus=" + health.overallStatus());
            writer.println("Liveness=" + health.livenessState());
            writer.println("PaymentGatewayReadiness=" + health.paymentGatewayReadiness());
            writer.println("JvmMemoryHealth=" + health.jvmMemoryHealth());
            return "UP".equals(health.overallStatus()) ? 0 : 1;
        }
    }

    @Command(name = "gc", mixinStandardHelpOptions = true, description = "Limpa vazamento de memoria simulado e aciona o Garbage Collector")
    public class GcCommand implements Callable<Integer> {

        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            var result = jvmMemoryService.fixMemoryLeakAndTriggerGc();
            PrintWriter writer = spec.commandLine().getOut();
            writer.println("=== FLASHTICKET ADMIN CLI :: GC ===");
            writer.println("Status=" + result.status());
            writer.println("RetainedLeakedMB=" + result.retainedMegabytesInStaticList());
            return 0;
        }
    }

    public record CliExecutionResult(
            String commandExecuted,
            int exitCode,
            String output) {
    }
}
