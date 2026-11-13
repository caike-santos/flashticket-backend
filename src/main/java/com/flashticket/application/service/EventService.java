package com.flashticket.application.service;

import com.flashticket.domain.exception.ResourceNotFoundException;
import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import com.flashticket.domain.pricing.FeeCalculatorService;
import com.flashticket.domain.repository.EventRepositoryPort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TÓPICO: SOLID (SRP e DIP)
 * Orquestra casos de uso de Eventos delegando cálculo de taxas para FeeCalculatorService
 * e persistência para a abstração EventRepositoryPort.
 */
@Service
public class EventService {

    private final EventRepositoryPort eventRepository;
    private final FeeCalculatorService feeCalculatorService;

    public EventService(EventRepositoryPort eventRepository, FeeCalculatorService feeCalculatorService) {
        this.eventRepository = eventRepository;
        this.feeCalculatorService = feeCalculatorService;
    }

    public Event createEvent(
            String title,
            String description,
            EventCategory category,
            LocalDateTime eventDate,
            int totalCapacity,
            BigDecimal basePrice) {
        BigDecimal platformFee = feeCalculatorService.calculatePlatformFee(category, basePrice);
        Event event = new Event(
                UUID.randomUUID(),
                title,
                description,
                category,
                eventDate,
                totalCapacity,
                totalCapacity,
                basePrice,
                platformFee);
        return eventRepository.save(event);
    }

    public Event getEventById(UUID id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento nao encontrado com ID: " + id));
    }

    public List<Event> listEvents(EventCategory category) {
        return eventRepository.findAll(category);
    }

    public Event updateEvent(
            UUID id,
            String title,
            String description,
            EventCategory category,
            LocalDateTime eventDate,
            int totalCapacity,
            BigDecimal basePrice) {
        Event existing = getEventById(id);
        BigDecimal platformFee = feeCalculatorService.calculatePlatformFee(category, basePrice);
        existing.updateDetails(title, description, category, eventDate, totalCapacity, basePrice, platformFee);
        return eventRepository.save(existing);
    }

    public void deleteEvent(UUID id) {
        if (!eventRepository.existsById(id)) {
            throw new ResourceNotFoundException("Evento nao encontrado com ID: " + id);
        }
        eventRepository.deleteById(id);
    }
}
