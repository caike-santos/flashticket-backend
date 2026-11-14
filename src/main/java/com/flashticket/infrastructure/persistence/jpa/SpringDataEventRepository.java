package com.flashticket.infrastructure.persistence.jpa;

import com.flashticket.domain.model.EventCategory;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataEventRepository extends JpaRepository<EventJpaEntity, UUID> {

    /**
     * TÓPICO: ÍNDICES NO BANCO DE DADOS
     * Esta query utiliza diretamente o índice composto 'idx_events_category_event_date (category, event_date)'
     * criado na migration V2 do Flyway!
     */
    List<EventJpaEntity> findByCategoryOrderByEventDateAsc(EventCategory category);

    List<EventJpaEntity> findAllByOrderByEventDateAsc();

    /**
     * TÓPICO: TRANSAÇÕES ACID & LOCK PESSIMISTA (SELECT ... FOR UPDATE)
     * Trava a linha do evento no banco até o fim da transação (@Transactional),
     * enfileirando outras transações que tentem comprar ingressos do mesmo evento.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EventJpaEntity e WHERE e.id = :id")
    Optional<EventJpaEntity> findByIdWithPessimisticLock(@Param("id") UUID id);
}
