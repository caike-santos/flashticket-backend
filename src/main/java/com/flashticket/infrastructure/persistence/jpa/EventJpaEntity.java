package com.flashticket.infrastructure.persistence.jpa;

import com.flashticket.domain.model.Event;
import com.flashticket.domain.model.EventCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TÓPICOS (Fase 2):
 * - Mapeamento Relacional validado pelo Flyway (ddl-auto: validate)
 * - Transações ACID & Controle de Concorrência com @Version (Optimistic Locking)
 */
@Entity
@Table(name = "events")
public class EventJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private EventCategory category;

    @Column(name = "event_date", nullable = false)
    private LocalDateTime eventDate;

    @Column(name = "total_capacity", nullable = false)
    private int totalCapacity;

    @Column(name = "available_tickets", nullable = false)
    private int availableTickets;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "platform_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal platformFee;

    /**
     * TÓPICO: TRANSAÇÕES ACID & LOCK OTIMISTA (@Version)
     * O Hibernate adiciona automaticamente "WHERE id = ? AND version = ?" no UPDATE
     * e incrementa version + 1. Se outra thread alterou o registro no meio tempo,
     * lança ObjectOptimisticLockingFailureException impedindo venda duplicada (Overselling)!
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected EventJpaEntity() {
    }

    public EventJpaEntity(
            UUID id,
            String title,
            String description,
            EventCategory category,
            LocalDateTime eventDate,
            int totalCapacity,
            int availableTickets,
            BigDecimal basePrice,
            BigDecimal platformFee,
            Long version) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.category = category;
        this.eventDate = eventDate;
        this.totalCapacity = totalCapacity;
        this.availableTickets = availableTickets;
        this.basePrice = basePrice;
        this.platformFee = platformFee;
        this.version = version;
    }

    public static EventJpaEntity fromDomain(Event event) {
        return new EventJpaEntity(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory(),
                event.getEventDate(),
                event.getTotalCapacity(),
                event.getAvailableTickets(),
                event.getBasePrice(),
                event.getPlatformFee(),
                event.getVersion()
        );
    }

    public Event toDomain() {
        return new Event(
                this.id,
                this.title,
                this.description,
                this.category,
                this.eventDate,
                this.totalCapacity,
                this.availableTickets,
                this.basePrice,
                this.platformFee,
                this.version
        );
    }

    public UUID getId() {
        return id;
    }

    public int getAvailableTickets() {
        return availableTickets;
    }

    public void setAvailableTickets(int availableTickets) {
        this.availableTickets = availableTickets;
    }

    public Long getVersion() {
        return version;
    }
}
