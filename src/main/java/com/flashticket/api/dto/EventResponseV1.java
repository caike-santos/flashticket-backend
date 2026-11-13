package com.flashticket.api.dto;

import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TÓPICO: VERSIONAMENTO DE API (v1)
 * Na v1, o contrato expunha apenas um campo 'price' simples.
 * Mantemos este DTO vivo para não quebrar aplicativos legados que ainda consomem /api/v1/events.
 */
public record EventResponseV1(
        UUID id,
        String title,
        EventCategory category,
        LocalDateTime eventDate,
        int availableTickets,
        BigDecimal price
) {
    public static EventResponseV1 fromDomain(Event event) {
        return new EventResponseV1(
                event.getId(),
                event.getTitle(),
                event.getCategory(),
                event.getEventDate(),
                event.getAvailableTickets(),
                event.getFinalPrice()
        );
    }
}
