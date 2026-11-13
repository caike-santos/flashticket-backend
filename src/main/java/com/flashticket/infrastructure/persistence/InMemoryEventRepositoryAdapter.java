package com.flashticket.infrastructure.persistence;

import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import com.flashticket.domain.repository.EventRepositoryPort;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryEventRepositoryAdapter implements EventRepositoryPort {

    private final Map<UUID, Event> storage = new ConcurrentHashMap<>();

    @Override
    public Event save(Event event) {
        storage.put(event.getId(), event);
        return event;
    }

    @Override
    public Optional<Event> findById(UUID id) {
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public List<Event> findAll(EventCategory categoryFilter) {
        return storage.values().stream()
                .filter(event -> categoryFilter == null || event.getCategory() == categoryFilter)
                .sorted(Comparator.comparing(Event::getEventDate))
                .toList();
    }

    @Override
    public boolean existsById(UUID id) {
        return storage.containsKey(id);
    }

    @Override
    public void deleteById(UUID id) {
        storage.remove(id);
    }
}
