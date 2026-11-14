package com.flashticket.api.graphql;

import com.flashticket.application.service.EventService;
import com.flashticket.domain.model.Event;
import com.flashticket.infrastructure.persistence.jpa.SpringDataTicketOrderRepository;
import com.flashticket.infrastructure.persistence.jpa.TicketOrderJpaEntity;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * TÓPICO (Fase 3): GraphQL e o Problema das Queries N+1
 * Compara lado a lado:
 * 1. @SchemaMapping individual (causa N+1 queries no banco)
 * 2. @BatchMapping / DataLoader (resolve o N+1 fazendo apenas 1 query em lote com WHERE event_id IN (...))
 */
@Controller
public class EventGraphqlController {

    private final EventService eventService;
    private final SpringDataTicketOrderRepository orderRepository;

    public EventGraphqlController(
            EventService eventService,
            SpringDataTicketOrderRepository orderRepository) {
        this.eventService = eventService;
        this.orderRepository = orderRepository;
    }

    // =========================================================================
    // CENÁRIO 1: ❌ REPRODUZINDO O PROBLEMA DAS QUERIES N+1 (@SchemaMapping)
    // =========================================================================

    @QueryMapping
    public List<EventWithNPlusOneDto> eventsWithNPlusOneProblem() {
        // Query #1: Busca todos os N eventos no banco (SELECT * FROM events)
        return eventService.listEvents(null).stream()
                .map(EventWithNPlusOneDto::fromDomain)
                .toList();
    }

    /**
     * ❌ PROBLEMA N+1:
     * O GraphQL chama este método 1 VEZ PARA CADA EVENTO retornado pela query pai!
     * Se houver 10 eventos, este método roda 10 vezes, disparando 10 SELECTs adicionais
     * (Total = 1 + 10 = 11 queries no banco!).
     */
    @SchemaMapping(typeName = "EventWithNPlusOne", field = "orders")
    public List<TicketOrderGraphDto> ordersWithNPlusOne(EventWithNPlusOneDto event) {
        return orderRepository.findByEventId(event.id()).stream()
                .map(TicketOrderGraphDto::fromEntity)
                .toList();
    }

    // =========================================================================
    // CENÁRIO 2: ✅ RESOLVENDO O N+1 COM @BatchMapping (DataLoader)
    // =========================================================================

    @QueryMapping
    public List<EventGraphDto> eventsOptimized() {
        // Query #1: Busca todos os N eventos no banco (SELECT * FROM events)
        return eventService.listEvents(null).stream()
                .map(EventGraphDto::fromDomain)
                .toList();
    }

    @QueryMapping
    public EventGraphDto eventById(@Argument UUID id) {
        return EventGraphDto.fromDomain(eventService.getEventById(id));
    }

    /**
     * ✅ SOLUÇÃO DO N+1 COM @BatchMapping:
     * Em vez de receber 1 evento por vez, recebe a LISTA INTEIRA de eventos (List<EventGraphDto>)!
     * Fazemos apenas 1 único SELECT no banco usando WHERE event_id IN (?, ?, ...)
     * e devolvemos um Map associando cada Evento aos seus Pedidos.
     * Total de queries no banco = SEMPRE 2 (1 para eventos + 1 para todos os pedidos)!
     */
    @BatchMapping(typeName = "EventGraph", field = "orders")
    public Map<EventGraphDto, List<TicketOrderGraphDto>> ordersBatchLoaded(List<EventGraphDto> events) {
        List<UUID> eventIds = events.stream()
                .map(EventGraphDto::id)
                .toList();

        // Query #2 (ÚNICA!): SELECT * FROM ticket_orders WHERE event_id IN (...)
        Map<UUID, List<TicketOrderGraphDto>> ordersByEventId = orderRepository.findByEventIdIn(eventIds)
                .stream()
                .map(TicketOrderGraphDto::fromEntity)
                .collect(Collectors.groupingBy(TicketOrderGraphDto::eventId));

        Map<EventGraphDto, List<TicketOrderGraphDto>> result = new LinkedHashMap<>();
        for (EventGraphDto event : events) {
            result.put(event, ordersByEventId.getOrDefault(event.id(), Collections.emptyList()));
        }
        return result;
    }

    // =========================================================================
    // DTOs GraphQL (Records imutáveis com equals/hashCode para o Map do BatchMapping)
    // =========================================================================

    public record EventWithNPlusOneDto(
            UUID id,
            String title,
            String category,
            int availableTickets,
            double finalPrice
    ) {
        public static EventWithNPlusOneDto fromDomain(Event event) {
            return new EventWithNPlusOneDto(
                    event.getId(),
                    event.getTitle(),
                    event.getCategory().name(),
                    event.getAvailableTickets(),
                    event.getFinalPrice().doubleValue()
            );
        }
    }

    public record EventGraphDto(
            UUID id,
            String title,
            String description,
            String category,
            int totalCapacity,
            int availableTickets,
            double basePrice,
            double platformFee,
            double finalPrice
    ) {
        public static EventGraphDto fromDomain(Event event) {
            return new EventGraphDto(
                    event.getId(),
                    event.getTitle(),
                    event.getDescription(),
                    event.getCategory().name(),
                    event.getTotalCapacity(),
                    event.getAvailableTickets(),
                    event.getBasePrice().doubleValue(),
                    event.getPlatformFee().doubleValue(),
                    event.getFinalPrice().doubleValue()
            );
        }
    }

    public record TicketOrderGraphDto(
            UUID id,
            UUID eventId,
            String customerEmail,
            int quantity,
            double unitPrice,
            double totalAmount,
            String status
    ) {
        public static TicketOrderGraphDto fromEntity(TicketOrderJpaEntity entity) {
            var domain = entity.toDomain();
            return new TicketOrderGraphDto(
                    domain.getId(),
                    domain.getEventId(),
                    domain.getCustomerEmail(),
                    domain.getQuantity(),
                    domain.getUnitPrice().doubleValue(),
                    domain.getTotalAmount().doubleValue(),
                    domain.getStatus().name()
            );
        }
    }
}
