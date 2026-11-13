package com.flashticket.api.controller;

import com.flashticket.api.dto.EventRequest;
import com.flashticket.api.dto.EventResponseV1;
import com.flashticket.api.dto.EventResponseV2;
import com.flashticket.application.service.EventService;
import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * TÓPICOS:
 * 1. API RESTful (substantivos no plural, verbos HTTP corretos, Status 201 Created + Location, 200 OK, 204 No Content).
 * 2. Versionamento de API (URI Versioning: /api/v1/events vs /api/v2/events + Header Versioning: X-API-Version).
 */
@RestController
@RequestMapping("/api")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    // =========================================================================
    // VERSÃO 1 (LEGADA / DEPRECIADA) - URI Versioning: /api/v1/events
    // =========================================================================

    @PostMapping("/v1/events")
    public ResponseEntity<EventResponseV1> createEventV1(@Valid @RequestBody EventRequest request) {
        Event created = eventService.createEvent(
                request.title(),
                request.description(),
                request.category(),
                request.eventDate(),
                request.totalCapacity(),
                request.basePrice());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location)
                .header("Deprecation", "true")
                .header("Link", "</api/v2/events>; rel=\"successor-version\"")
                .body(EventResponseV1.fromDomain(created));
    }

    @GetMapping("/v1/events/{id}")
    public ResponseEntity<EventResponseV1> getEventByIdV1(@PathVariable UUID id) {
        Event event = eventService.getEventById(id);
        return ResponseEntity.ok()
                .header("Deprecation", "true")
                .header("Link", "</api/v2/events/" + id + ">; rel=\"successor-version\"")
                .body(EventResponseV1.fromDomain(event));
    }

    // =========================================================================
    // VERSÃO 2 (ATUAL) - URI Versioning: /api/v2/events
    // =========================================================================

    @PostMapping("/v2/events")
    public ResponseEntity<EventResponseV2> createEventV2(@Valid @RequestBody EventRequest request) {
        Event created = eventService.createEvent(
                request.title(),
                request.description(),
                request.category(),
                request.eventDate(),
                request.totalCapacity(),
                request.basePrice());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(EventResponseV2.fromDomain(created));
    }

    @GetMapping("/v2/events")
    public ResponseEntity<List<EventResponseV2>> listEventsV2(
            @RequestParam(required = false) EventCategory category) {
        List<EventResponseV2> response = eventService.listEvents(category).stream()
                .map(EventResponseV2::fromDomain)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/v2/events/{id}")
    public ResponseEntity<EventResponseV2> getEventByIdV2(@PathVariable UUID id) {
        Event event = eventService.getEventById(id);
        return ResponseEntity.ok(EventResponseV2.fromDomain(event));
    }

    @PutMapping("/v2/events/{id}")
    public ResponseEntity<EventResponseV2> updateEventV2(
            @PathVariable UUID id,
            @Valid @RequestBody EventRequest request) {
        Event updated = eventService.updateEvent(
                id,
                request.title(),
                request.description(),
                request.category(),
                request.eventDate(),
                request.totalCapacity(),
                request.basePrice());
        return ResponseEntity.ok(EventResponseV2.fromDomain(updated));
    }

    @DeleteMapping("/v2/events/{id}")
    public ResponseEntity<Void> deleteEventV2(@PathVariable UUID id) {
        eventService.deleteEvent(id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // ESTRATÉGIA ALTERNATIVA: Versionamento via Header (X-API-Version)
    // Mesma URL (/api/events/{id}), contrato selecionado pelo cabeçalho HTTP.
    // =========================================================================

    @GetMapping(value = "/events/{id}", headers = "X-API-Version=1")
    public ResponseEntity<EventResponseV1> getEventByHeaderV1(@PathVariable UUID id) {
        return getEventByIdV1(id);
    }

    @GetMapping(value = "/events/{id}", headers = "X-API-Version=2")
    public ResponseEntity<EventResponseV2> getEventByHeaderV2(@PathVariable UUID id) {
        return getEventByIdV2(id);
    }
}
