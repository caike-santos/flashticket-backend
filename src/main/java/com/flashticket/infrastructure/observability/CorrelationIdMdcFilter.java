package com.flashticket.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TÓPICO (Fase 8): Logs Estruturados para Debug e MDC (Mapped Diagnostic Context / Correlation-ID).
 *
 * Qual problema o MDC (Correlation-ID) resolve em Produção?
 * - Imagine 500 clientes comprando ingressos exatamente no mesmo segundo. Os logs das 500
 *   requisições saem todos misturados no console/Kibana/Datadog.
 * - Como saber quais linhas de log do Controller, do Service e do Repository pertencem à
 *   requisição do cliente João que deu erro?
 * - Este filtro gera (ou lê do header 'X-Correlation-ID') um ID único para a requisição
 *   e o coloca no MDC (um mapa ThreadLocal do SLF4J).
 * - Automaticamente, TODAS as mensagens de log (DEBUG, INFO, WARN, ERROR) daquela thread
 *   passam a exibir [corrId=req_...] sem precisar passar o ID como parâmetro de método!
 * - Regra de Ouro: SEMPRE chamar MDC.clear() no bloco finally para não vazar o ID para
 *   a próxima requisição que reutilizar a mesma thread do pool do Tomcat!
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdMdcFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdMdcFilter.class);

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_CORRELATION_ID_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String incomingCorrelationId = request.getHeader(CORRELATION_ID_HEADER);
        String correlationId = (incomingCorrelationId != null && !incomingCorrelationId.isBlank())
                ? incomingCorrelationId.trim()
                : "req_" + UUID.randomUUID().toString().substring(0, 8);

        long startNanos = System.nanoTime();
        try {
            // 1. Injeta o Correlation-ID no MDC (ThreadLocal do SLF4J) e no Header de resposta HTTP
            MDC.put(MDC_CORRELATION_ID_KEY, correlationId);
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            log.debug("Iniciando requisicao HTTP: {} {}", request.getMethod(), request.getRequestURI());

            filterChain.doFilter(request, response);

        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("Requisicao concluida: {} {} -> status={} ({} ms)",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);

            // 2. OBRIGATÓRIO: Limpa o ThreadLocal do MDC antes de devolver a thread ao pool do Tomcat!
            MDC.clear();
        }
    }
}
