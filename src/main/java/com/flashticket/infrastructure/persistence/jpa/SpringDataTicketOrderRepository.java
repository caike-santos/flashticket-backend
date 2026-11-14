package com.flashticket.infrastructure.persistence.jpa;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataTicketOrderRepository extends JpaRepository<TicketOrderJpaEntity, UUID> {

    /**
     * Utiliza o índice 'idx_ticket_orders_customer_email' criado na V2 do Flyway.
     */
    List<TicketOrderJpaEntity> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    /**
     * Utiliza o índice 'idx_ticket_orders_event_id' criado na V2 do Flyway.
     */
    List<TicketOrderJpaEntity> findByEventId(UUID eventId);
}
