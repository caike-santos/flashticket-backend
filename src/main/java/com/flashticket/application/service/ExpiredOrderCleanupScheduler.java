package com.flashticket.application.service;

import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.model.OrderStatus;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import com.flashticket.infrastructure.config.CacheConfig;
import com.flashticket.infrastructure.persistence.jpa.EventJpaEntity;
import com.flashticket.infrastructure.persistence.jpa.SpringDataEventRepository;
import com.flashticket.infrastructure.persistence.jpa.SpringDataTicketOrderRepository;
import com.flashticket.infrastructure.persistence.jpa.TicketOrderJpaEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TÓPICO (Fase 6): Utilidade de Cron Jobs / Tarefas Agendadas (@Scheduled).
 *
 * Qual problema real de negócio o Cron Job resolve no FlashTicket?
 * - Quando um cliente inicia a compra, o pedido nasce como PENDING e os ingressos ficam
 *   reservados por 15 minutos (expiresAt = now + 15 min).
 * - Se o cliente fechar a aba do navegador e nunca pagar, aqueles ingressos ficariam
 *   "presos" para sempre se não houvesse uma rotina de limpeza!
 * - Este Job roda periodicamente em background (@Scheduled), busca todos os pedidos PENDING
 *   cujo expiresAt já passou (usando o índice composto 'idx_ticket_orders_status_expires_at'),
 *   muda o status para CANCELLED e DEVOLVE os ingressos ao estoque do evento!
 */
@Service
public class ExpiredOrderCleanupScheduler {

    private final SpringDataTicketOrderRepository orderRepository;
    private final SpringDataEventRepository eventRepository;
    private final AuditLogRepositoryPort auditLogRepository;
    private final CacheManager cacheManager;

    public ExpiredOrderCleanupScheduler(
            SpringDataTicketOrderRepository orderRepository,
            SpringDataEventRepository eventRepository,
            AuditLogRepositoryPort auditLogRepository,
            CacheManager cacheManager) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.auditLogRepository = auditLogRepository;
        this.cacheManager = cacheManager;
    }

    /**
     * Roda automaticamente a cada 60 segundos (configurável) e também pode ser acionado
     * manualmente via endpoint administrativo ou CLI para testes imediatos.
     */
    @Scheduled(fixedDelayString = "${flashticket.jobs.expired-orders-delay-ms:60000}")
    @Transactional
    public CleanupExecutionReport cancelExpiredPendingOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<TicketOrderJpaEntity> expiredOrders =
                orderRepository.findByStatusAndExpiresAtBefore(OrderStatus.PENDING, now);

        int cancelledOrdersCount = 0;
        int totalTicketsRestored = 0;
        List<UUID> cancelledOrderIds = new ArrayList<>();

        for (TicketOrderJpaEntity order : expiredOrders) {
            order.setStatus(OrderStatus.CANCELLED);
            orderRepository.save(order);

            EventJpaEntity event = eventRepository.findByIdWithPessimisticLock(order.getEventId())
                    .orElse(null);
            if (event != null) {
                int restoredStock = event.getAvailableTickets() + order.getQuantity();
                event.setAvailableTickets(restoredStock);
                eventRepository.save(event);
                evictEventCaches(event.getId());
            }

            cancelledOrdersCount++;
            totalTicketsRestored += order.getQuantity();
            cancelledOrderIds.add(order.getId());

            auditLogRepository.save(AuditLog.create(
                    "EXPIRED_ORDER_CANCELLED_BY_CRON",
                    order.getId().toString(),
                    "CRON_SCHEDULER",
                    Map.of(
                            "eventId", order.getEventId().toString(),
                            "restoredTickets", order.getQuantity(),
                            "expiredAt", order.getExpiresAt().toString()
                    )
            ));
        }

        return new CleanupExecutionReport(
                cancelledOrdersCount,
                totalTicketsRestored,
                cancelledOrderIds,
                Instant.now());
    }

    @Transactional
    public void forceExpireOrderForTesting(UUID orderId) {
        TicketOrderJpaEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Pedido nao encontrado: " + orderId));
        order.setExpiresAt(LocalDateTime.now().minusMinutes(20));
        orderRepository.saveAndFlush(order);
    }

    private void evictEventCaches(UUID eventId) {
        Cache byIdCache = cacheManager.getCache(CacheConfig.EVENT_BY_ID_CACHE);
        if (byIdCache != null) {
            byIdCache.evict(eventId);
        }
        Cache listCache = cacheManager.getCache(CacheConfig.EVENTS_LIST_CACHE);
        if (listCache != null) {
            listCache.clear();
        }
    }

    public record CleanupExecutionReport(
            int cancelledOrdersCount,
            int totalTicketsRestoredToStock,
            List<UUID> cancelledOrderIds,
            Instant executedAt) {
    }
}
