package com.flashticket.application.service;

import com.flashticket.domain.exception.ResourceNotFoundException;
import com.flashticket.domain.model.AuditLog;
import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import com.flashticket.domain.pricing.FeeCalculatorService;
import com.flashticket.domain.repository.AuditLogRepositoryPort;
import com.flashticket.domain.repository.EventRepositoryPort;
import com.flashticket.infrastructure.config.CacheConfig;
import com.flashticket.infrastructure.security.XssSanitizer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/**
 * TÓPICOS:
 * - Fase 1: SOLID (SRP e DIP)
 * - Fase 4: Cache-Aside (@Cacheable na leitura e @CacheEvict na escrita para evitar dados defasados)
 * - Fase 4: Persistência NoSQL (MongoDB) de logs de auditoria com payload JSON flexível
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepositoryPort eventRepository;
    private final FeeCalculatorService feeCalculatorService;
    private final AuditLogRepositoryPort auditLogRepository;

    public EventService(
            EventRepositoryPort eventRepository,
            FeeCalculatorService feeCalculatorService,
            AuditLogRepositoryPort auditLogRepository) {
        this.eventRepository = eventRepository;
        this.feeCalculatorService = feeCalculatorService;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * @CacheEvict: Ao criar um evento, limpa o cache de listagem para que a vitrine mostre o novo evento!
     */
    @CacheEvict(value = CacheConfig.EVENTS_LIST_CACHE, allEntries = true)
    public Event createEvent(
            String title,
            String description,
            EventCategory category,
            LocalDateTime eventDate,
            int totalCapacity,
            BigDecimal basePrice) {
        String safeTitle = XssSanitizer.sanitize(title);
        String safeDescription = XssSanitizer.sanitize(description);
        BigDecimal platformFee = feeCalculatorService.calculatePlatformFee(category, basePrice);
        Event event = new Event(
                UUID.randomUUID(),
                safeTitle,
                safeDescription,
                category,
                eventDate,
                totalCapacity,
                totalCapacity,
                basePrice,
                platformFee);
        Event saved = eventRepository.save(event);
        log.info("Evento criado: id={}, title='{}', category={}, finalPrice={}",
                saved.getId(), saved.getTitle(), saved.getCategory(), saved.getFinalPrice());

        // Registra documento NoSQL (MongoDB) com estrutura JSON específica de criação de evento
        auditLogRepository.save(AuditLog.create(
                "EVENT_CREATED",
                saved.getId().toString(),
                "ORGANIZER",
                Map.of(
                        "title", saved.getTitle(),
                        "category", saved.getCategory().name(),
                        "totalCapacity", saved.getTotalCapacity(),
                        "basePrice", saved.getBasePrice(),
                        "platformFee", saved.getPlatformFee(),
                        "finalPrice", saved.getFinalPrice()
                )
        ));

        return saved;
    }

    /**
     * @Cacheable (Padrão Cache-Aside):
     * - 1ª chamada (CACHE MISS): Executa o método, vai no PostgreSQL e salva o retorno no Cache (Redis/Caffeine).
     * - 2ª chamada em diante (CACHE HIT): Retorna direto da memória RAM sem nem entrar neste método (0 SELECTs no banco)!
     */
    @Cacheable(value = CacheConfig.EVENT_BY_ID_CACHE, key = "#id")
    public Event getEventById(UUID id) {
        log.debug("CACHE MISS: Buscando evento id={} no banco de dados", id);
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento nao encontrado com ID: " + id));
    }

    @Cacheable(value = CacheConfig.EVENTS_LIST_CACHE, key = "#category != null ? #category.name() : 'ALL'")
    public List<Event> listEvents(EventCategory category) {
        log.debug("CACHE MISS: Listando eventos no banco para category={}", category);
        return eventRepository.findAll(category);
    }

    /**
     * @Caching / @CacheEvict: Ao atualizar um evento, remove tanto o cache individual (#id) quanto a listagem!
     */
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.EVENT_BY_ID_CACHE, key = "#id"),
            @CacheEvict(value = CacheConfig.EVENTS_LIST_CACHE, allEntries = true)
    })
    public Event updateEvent(
            UUID id,
            String title,
            String description,
            EventCategory category,
            LocalDateTime eventDate,
            int totalCapacity,
            BigDecimal basePrice) {
        Event existing = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento nao encontrado com ID: " + id));
        BigDecimal oldPrice = existing.getBasePrice();

        String safeTitle = XssSanitizer.sanitize(title);
        String safeDescription = XssSanitizer.sanitize(description);
        BigDecimal platformFee = feeCalculatorService.calculatePlatformFee(category, basePrice);
        existing.updateDetails(safeTitle, safeDescription, category, eventDate, totalCapacity, basePrice, platformFee);
        Event saved = eventRepository.save(existing);

        // Registra documento NoSQL com campos diferentes (oldBasePrice -> newBasePrice) sem precisar de Migration!
        auditLogRepository.save(AuditLog.create(
                "EVENT_UPDATED",
                saved.getId().toString(),
                "ORGANIZER",
                Map.of(
                        "oldBasePrice", oldPrice,
                        "newBasePrice", saved.getBasePrice(),
                        "newTotalCapacity", saved.getTotalCapacity()
                )
        ));

        return saved;
    }

    @Caching(evict = {
            @CacheEvict(value = CacheConfig.EVENT_BY_ID_CACHE, key = "#id"),
            @CacheEvict(value = CacheConfig.EVENTS_LIST_CACHE, allEntries = true)
    })
    public void deleteEvent(UUID id) {
        if (!eventRepository.existsById(id)) {
            throw new ResourceNotFoundException("Evento nao encontrado com ID: " + id);
        }
        eventRepository.deleteById(id);

        auditLogRepository.save(AuditLog.create(
                "EVENT_DELETED",
                id.toString(),
                "ORGANIZER",
                Map.of("deletedEventId", id.toString())
        ));
    }
}
