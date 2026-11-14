package com.flashticket.infrastructure.persistence.jpa;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataTicketOrderRepository extends JpaRepository<TicketOrderJpaEntity, UUID> {

    /**
     * Utiliza o índice 'idx_ticket_orders_customer_email' criado na V2 do Flyway.
     */
    List<TicketOrderJpaEntity> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    /**
     * CAUSA N+1 QUANDO CHAMADO EM LOOP PARA CADA EVENTO:
     * Executa 1 SELECT individual por evento (SELECT * FROM ticket_orders WHERE event_id = ?).
     */
    List<TicketOrderJpaEntity> findByEventId(UUID eventId);

    /**
     * SOLUÇÃO DO N+1 (USADO PELO @BatchMapping DO GRAPHQL):
     * Busca os pedidos de TODOS os eventos de uma única vez com cláusula IN:
     * SELECT * FROM ticket_orders WHERE event_id IN (?, ?, ?, ...)
     */
    List<TicketOrderJpaEntity> findByEventIdIn(Collection<UUID> eventIds);
}
