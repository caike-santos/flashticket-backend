package com.flashticket.application.service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * TÓPICOS (Fase 8):
 * 1. Gerenciamento de Memória na JVM (Stack vs Heap: Young Generation [Eden/Survivor], Old Generation e Metaspace)
 * 2. Garbage Collection (G1GC / ZGC: Minor GC vs Major/Full GC, contagem de coletas e tempo de pausa Stop-The-World)
 * 3. Vazamento de Memória (Memory Leak em Java): Simulação controlada de objetos retidos em coleção estática
 *    (GC Root) que o Garbage Collector NÃO consegue limpar até a referência ser removida!
 * 4. Health Check customizado integrando o estado do Gateway de Pagamento (Circuit Breaker).
 */
@Service
public class JvmMemoryAndGcLabService {

    private static final Logger log = LoggerFactory.getLogger(JvmMemoryAndGcLabService.class);

    /**
     * CAUSA CLÁSSICA DE MEMORY LEAK EM JAVA:
     * Uma lista estática (GC Root) sem limite máximo e sem remoção (TTL/eviction).
     * Enquanto os arrays byte[] estiverem referenciados aqui dentro, o Garbage Collector
     * é PROIBIDO de liberá-los da memória Heap (eles são promovidos para a Old Generation)!
     */
    private static final List<byte[]> UNBOUNDED_STATIC_LEAK_CACHE = new CopyOnWriteArrayList<>();

    private final ResilientPaymentGatewayService resilientGatewayService;

    public JvmMemoryAndGcLabService(ResilientPaymentGatewayService resilientGatewayService) {
        this.resilientGatewayService = resilientGatewayService;
    }

    public MemoryLeakSimulationResult simulateMemoryLeak(int megabytesToLeak) {
        int safeMb = Math.min(Math.max(megabytesToLeak, 1), 25); // Limite seguro de até 25 MB para não derrubar os testes
        long heapBeforeBytes = getHeapUsedBytes();

        for (int i = 0; i < safeMb; i++) {
            byte[] oneMegabyteChunk = new byte[1024 * 1024];
            // Preenche alguns bytes para garantir que a página de memória seja efetivamente alocada
            oneMegabyteChunk[0] = 1;
            oneMegabyteChunk[oneMegabyteChunk.length - 1] = 1;
            UNBOUNDED_STATIC_LEAK_CACHE.add(oneMegabyteChunk);
        }

        long heapAfterBytes = getHeapUsedBytes();
        log.warn("MEMORY LEAK SIMULADO: Alocados +{} MB em lista estatica sem remocao (Total retido no leak={} MB)",
                safeMb, UNBOUNDED_STATIC_LEAK_CACHE.size());

        return new MemoryLeakSimulationResult(
                "MEMORY_LEAK_ACTIVE",
                UNBOUNDED_STATIC_LEAK_CACHE.size(),
                bytesToMb(heapBeforeBytes),
                bytesToMb(heapAfterBytes),
                "Objetos retidos em 'static List<byte[]>' (GC Root)! Mesmo rodando o Garbage Collector agora, essa memoria NAO podera ser liberada enquanto a lista nao for limpa.");
    }

    public MemoryLeakSimulationResult fixMemoryLeakAndTriggerGc() {
        int leakedMbRemoved = UNBOUNDED_STATIC_LEAK_CACHE.size();
        long heapBeforeBytes = getHeapUsedBytes();

        // 1. Remove as referências fortes (torna os objetos INALCANÇÁVEIS / Unreachable para o GC!)
        UNBOUNDED_STATIC_LEAK_CACHE.clear();

        // 2. Sugere à JVM a execução imediata do Garbage Collector
        System.gc();

        long heapAfterBytes = getHeapUsedBytes();
        log.info("MEMORY LEAK CORRIGIDO: {} MB desreferenciados da lista estatica e Garbage Collector acionado!",
                leakedMbRemoved);

        return new MemoryLeakSimulationResult(
                "MEMORY_LEAK_CLEARED_AND_GC_TRIGGERED",
                UNBOUNDED_STATIC_LEAK_CACHE.size(),
                bytesToMb(heapBeforeBytes),
                bytesToMb(heapAfterBytes),
                "Referencias removidas (" + leakedMbRemoved + " MB liberados do GC Root) e System.gc() executado com sucesso!");
    }

    public JvmMemoryAndGcSnapshot inspectJvmMemoryAndGc() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        List<Map<String, Object>> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            Map<String, Object> poolInfo = new LinkedHashMap<>();
            poolInfo.put("poolName", pool.getName());
            poolInfo.put("type", pool.getType().name());
            poolInfo.put("usedMb", bytesToMb(usage.getUsed()));
            poolInfo.put("committedMb", bytesToMb(usage.getCommitted()));
            pools.add(poolInfo);
        }

        List<Map<String, Object>> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> gcInfo = new LinkedHashMap<>();
            gcInfo.put("collectorName", gc.getName());
            gcInfo.put("collectionCount", gc.getCollectionCount());
            gcInfo.put("collectionTimeMs", gc.getCollectionTime());
            collectors.add(gcInfo);
        }

        return new JvmMemoryAndGcSnapshot(
                bytesToMb(heap.getUsed()),
                bytesToMb(heap.getCommitted()),
                bytesToMb(heap.getMax()),
                bytesToMb(nonHeap.getUsed()),
                threadBean.getThreadCount(),
                threadBean.getPeakThreadCount(),
                UNBOUNDED_STATIC_LEAK_CACHE.size(),
                pools,
                collectors,
                Instant.now());
    }

    public CustomPlatformHealthReport evaluateCustomHealth() {
        var resilience = resilientGatewayService.getDiagnosticsStatus();
        boolean gatewayUp = resilience.circuitState() != ResilientPaymentGatewayService.CircuitState.OPEN;
        boolean memoryHealthy = UNBOUNDED_STATIC_LEAK_CACHE.size() < 20;

        String overallStatus = (gatewayUp && memoryHealthy) ? "UP" : "DEGRADED";
        return new CustomPlatformHealthReport(
                overallStatus,
                "UP",
                gatewayUp ? "UP" : "DOWN (CIRCUIT_BREAKER_OPEN)",
                memoryHealthy ? "UP" : "WARN_HIGH_LEAK_RETENTION",
                UNBOUNDED_STATIC_LEAK_CACHE.size(),
                MDC.get("correlationId"),
                Instant.now());
    }

    private static long getHeapUsedBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static double bytesToMb(long bytes) {
        if (bytes < 0) {
            return -1;
        }
        return Math.round((bytes / (1024.0 * 1024.0)) * 100.0) / 100.0;
    }

    public record MemoryLeakSimulationResult(
            String status,
            int retainedMegabytesInStaticList,
            double heapUsedBeforeMb,
            double heapUsedAfterMb,
            String explanation) {
    }

    public record JvmMemoryAndGcSnapshot(
            double heapUsedMb,
            double heapCommittedMb,
            double heapMaxMb,
            double metaspaceAndNonHeapUsedMb,
            int activeThreadsStackCount,
            int peakThreadsCount,
            int leakedMegabytesRetained,
            List<Map<String, Object>> memoryPools,
            List<Map<String, Object>> garbageCollectors,
            Instant capturedAt) {
    }

    public record CustomPlatformHealthReport(
            String overallStatus,
            String livenessState,
            String paymentGatewayReadiness,
            String jvmMemoryHealth,
            int leakedMegabytesRetained,
            String activeCorrelationIdInMdc,
            Instant checkedAt) {
    }
}
