package com.flashticket.application.service;

import com.flashticket.domain.exception.ResourceNotFoundException;
import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.OrderStatus;
import com.flashticket.domain.model.TicketOrder;
import com.flashticket.infrastructure.persistence.jpa.EventJpaEntity;
import com.flashticket.infrastructure.persistence.jpa.SpringDataEventRepository;
import com.flashticket.infrastructure.persistence.jpa.SpringDataTicketOrderRepository;
import com.flashticket.infrastructure.persistence.jpa.TicketOrderJpaEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TÓPICO: TRANSAÇÕES ACID (@Transactional)
 * - Atomicidade (A): Abater o estoque na tabela 'events' e inserir o pedido na tabela 'ticket_orders'
 *   acontecem como uma unidade indivisível (Tudo ou Nada). Se der erro no meio, o banco faz ROLLBACK!
 * - Consistência (C): As regras de domínio (availableTickets >= 0) e CHECK constraints do banco nunca são violadas.
 * - Isolamento (I): Controlado via nível de isolamento READ_COMMITTED + Lock Otimista (@Version) ou Pessimista (FOR UPDATE).
 * - Durabilidade (D): Após o COMMIT, os dados ficam gravados no Write-Ahead Log (WAL) do banco.
 */
@Service
public class TicketPurchaseService {

    private static final int RESERVATION_EXPIRATION_MINUTES = 15;

    private final SpringDataEventRepository eventRepository;
    private final SpringDataTicketOrderRepository orderRepository;

    public TicketPurchaseService(
            SpringDataEventRepository eventRepository,
            SpringDataTicketOrderRepository orderRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
    }

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
        // Se uma falha ocorrer DEPOIS de abater o estoque e ANTES de salvar o pedido,
        // o @Transactional desfaz (ROLLBACK) o UPDATE na tabela 'events' automaticamente!
        if (simulateFailureAfterStockDeduction) {
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
        return savedOrder.toDomain();
    }

    @Transactional(readOnly = true)
    public List<TicketOrder> listOrdersByCustomer(String customerEmail) {
        return orderRepository.findByCustomerEmailOrderByCreatedAtDesc(customerEmail).stream()
                .map(TicketOrderJpaEntity::toDomain)
                .toList();
    }
}
