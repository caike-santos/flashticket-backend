package com.flashticket.api.dto;

import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TÓPICO: VERSIONAMENTO DE API (v2)
 * Na v2, o preço foi reestruturado em um objeto detalhado 'pricing' (Breaking Change em relação à v1),
 * exibindo transparência entre preço base e taxa da plataforma calculada via Strategy (SOLID).
 */
public record EventResponseV2(
        UUID id,
        String title,
        String description,
        EventCategory category,
        LocalDateTime eventDate,
        int totalCapacity,
        int availableTickets,
        PricingBreakdown pricing
) {
    public record PricingBreakdown(
            BigDecimal basePrice,
            BigDecimal platformFee,
            BigDecimal finalPrice,
            String currency
    ) {
    }

    public static EventResponseV2 fromDomain(Event event) {
        return new EventResponseV2(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory(),
                event.getEventDate(),
                event.getTotalCapacity(),
                event.getAvailableTickets(),
                new PricingBreakdown(
                        event.getBasePrice(),
                        event.getPlatformFee(),
                        event.getFinalPrice(),
                        "BRL"
                )
        );
    }
}
