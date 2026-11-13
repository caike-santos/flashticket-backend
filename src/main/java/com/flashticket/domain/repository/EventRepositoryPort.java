package com.flashticket.domain.repository;

import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TÓPICO: SOLID - DIP (Dependency Inversion Principle)
 * A camada de domínio/serviço depende desta abstração (porta), e não da tecnologia de banco.
 * Na Fase 1 usamos memória; na Fase 2 plugaremos PostgreSQL + Flyway sem quebrar o domínio!
 */
public interface EventRepositoryPort {

    Event save(Event event);

    Optional<Event> findById(UUID id);

    List<Event> findAll(EventCategory categoryFilter);

    boolean existsById(UUID id);

    void deleteById(UUID id);
}
