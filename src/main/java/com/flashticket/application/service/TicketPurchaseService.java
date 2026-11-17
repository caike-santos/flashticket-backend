package com.flashticket.application.service;

import com.flashticket.domain.exception.ResourceNotFoundException;
import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.OrderStatus;
import com.flashticket.domain.model.TicketOrder;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import com.flashticket.infrastructure.config.CacheConfig;
import com.flashticket.infrastructure.persistence.jpa.EventJpaEntity;
import com.flashticket.infrastructure.persistence.jpa.SpringDataEventRepository;
import com.flashticket.infrastructure.persistence.jpa.SpringDataTicketOrderRepository;
import com.flashticket.infrastructure.persistence.jpa.TicketOrderJpaEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TÓPICO: TRANSAÇÕES ACID (@Transactional) + Invalidação de Cache (@CacheEvict) + Auditoria NoSQL
 */
@Service
public class TicketPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(TicketPurchaseService.class);

    private static final int RESERVATION_EXPIRATION_MINUTES = 15;

    private final SpringDataEventRepository eventRepository;
    private final SpringDataTicketOrderRepository orderRepository;
    private final AuditLogRepositoryPort auditLogRepository;

    public TicketPurchaseService(
            SpringDataEventRepository eventRepository,
            SpringDataTicketOrderRepository orderRepository,
            AuditLogRepositoryPort auditLogRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * TÓPICO (Fase 4): @CacheEvict em mudanças de estoque!
     * Sempre que uma compra é concluída, o availableTickets do evento diminui.
     * Por isso invalidamos o cache desse evento (#eventId) e da vitrine geral para ninguém ver estoque defasado!
     */
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.EVENT_BY_ID_CACHE, key = "#eventId"),
            @CacheEvict(value = CacheConfig.EVENTS_LIST_CACHE, allEntries = true)
    })
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TicketOrder purchaseTickets(
            UUID eventId,
            String customerEmail,
            int quantity,
            boolean usePessimisticLock,
            boolean simulateFailureAfterStockDeduction) {

        // 1. Busca o evento (com Lock Pessimista SELECT ... FOR UPDATE ou Lock Otimista @Version)
        EventJpaEntity eventEntity = usePessimisticLock
                ? eventRepository.findByIdWithPessimisticLock(eventId)
                        .orElseThrow(() -> new ResourceNotFoundException("Evento nao encontrado com ID: " + eventId))
                : eventRepository.findById(eventId)
                        .orElseThrow(() -> new ResourceNotFoundException("Evento nao encontrado com ID: " + eventId));

        // 2. Aplica regra de domínio: valida se há estoque e decrementa availableTickets
        Event domainEvent = eventEntity.toDomain();
        domainEvent.reserveTickets(quantity);

        // 3. Atualiza o estoque no banco (O Hibernate valida a coluna @Version aqui!)
        eventEntity.setAvailableTickets(domainEvent.getAvailableTickets());
        eventRepository.saveAndFlush(eventEntity);

        // 4. Demonstração prática da ATOMICIDADE (A do ACID):
        if (simulateFailureAfterStockDeduction) {
            log.error("Falha critica simulada apos abater estoque do eventId={}! Acionando ROLLBACK ACID.", eventId);
            throw new IllegalStateException(
                    "Falha simulada apos abater estoque! O @Transactional fara ROLLBACK e devolvera o ingresso.");
        }

        // 5. Cria e salva o pedido de reserva (PENDING por 15 minutos)
        LocalDateTime now = LocalDateTime.now();
        BigDecimal unitPrice = domainEvent.getFinalPrice();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));

        TicketOrder order = new TicketOrder(
                UUID.randomUUID(),
                eventId,
                customerEmail,
                quantity,
                unitPrice,
                totalAmount,
                OrderStatus.PENDING,
                now,
                now.plusMinutes(RESERVATION_EXPIRATION_MINUTES)
        );

        TicketOrderJpaEntity savedOrder = orderRepository.saveAndFlush(TicketOrderJpaEntity.fromDomain(order));
        TicketOrder result = savedOrder.toDomain();
        log.info("Compra reservada (PENDING): orderId={}, eventId={}, quantity={}, remainingStock={}",
                result.getId(), eventId, quantity, domainEvent.getAvailableTickets());

        // 6. Grava Documento NoSQL (MongoDB) com metadados específicos da compra de ingresso
        auditLogRepository.save(AuditLog.create(
                "TICKET_PURCHASED",
                eventId.toString(),
                customerEmail,
                Map.of(
                        "orderId", result.getId().toString(),
                        "quantity", quantity,
                        "totalAmount", totalAmount,
                        "remainingTickets", domainEvent.getAvailableTickets(),
                        "lockStrategy", usePessimisticLock ? "PESSIMISTIC_WRITE" : "OPTIMISTIC_VERSION"
                )
        ));

        return result;
    }

    @Transactional(readOnly = true)
    public List<TicketOrder> listOrdersByCustomer(String customerEmail) {
        return orderRepository.findByCustomerEmailOrderByCreatedAtDesc(customerEmail).stream()
                .map(TicketOrderJpaEntity::toDomain)
                .toList();
    }
}
