package com.flashticket.infrastructure.persistence.jpa;

import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import com.flashticket.domain.repository.EventRepositoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * TÓPICO: SOLID - DIP (Dependency Inversion Principle) na prática!
 * Ao anotar este Adapter JPA com @Primary, o EventService passa a persistir no banco relacional
 * (validado pelo Flyway) sem precisarmos mudar uma única linha do EventService!
 */
@Primary
@Repository
public class JpaEventRepositoryAdapter implements EventRepositoryPort {

    private final SpringDataEventRepository springDataRepository;

    public JpaEventRepositoryAdapter(SpringDataEventRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public Event save(Event event) {
        EventJpaEntity entity = EventJpaEntity.fromDomain(event);
        EventJpaEntity saved = springDataRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<Event> findById(UUID id) {
        return springDataRepository.findById(id).map(EventJpaEntity::toDomain);
    }

    @Override
    public List<Event> findAll(EventCategory categoryFilter) {
        List<EventJpaEntity> entities = (categoryFilter == null)
                ? springDataRepository.findAllByOrderByEventDateAsc()
                : springDataRepository.findByCategoryOrderByEventDateAsc(categoryFilter);

        return entities.stream().map(EventJpaEntity::toDomain).toList();
    }

    @Override
    public boolean existsById(UUID id) {
        return springDataRepository.existsById(id);
    }

    @Override
    public void deleteById(UUID id) {
        springDataRepository.deleteById(id);
    }
}
