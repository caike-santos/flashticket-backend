package com.flashticket.domain.model;

import com.flashticket.domain.exception.BusinessRuleException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class Event implements Serializable {

    private final UUID id;
    private String title;
    private String description;
    private EventCategory category;
    private LocalDateTime eventDate;
    private int totalCapacity;
    private int availableTickets;
    private BigDecimal basePrice;
    private BigDecimal platformFee;
    private Long version;

    public Event(
            UUID id,
            String title,
            String description,
            EventCategory category,
            LocalDateTime eventDate,
            int totalCapacity,
            int availableTickets,
            BigDecimal basePrice,
            BigDecimal platformFee) {
        this(id, title, description, category, eventDate, totalCapacity, availableTickets, basePrice, platformFee, null);
    }

    public Event(
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
        this.id = id != null ? id : UUID.randomUUID();
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

    public BigDecimal getFinalPrice() {
        return basePrice.add(platformFee);
    }

    public void reserveTickets(int quantity) {
        if (quantity <= 0) {
            throw new BusinessRuleException("A quantidade de ingressos deve ser maior que zero.");
        }
        if (this.availableTickets < quantity) {
            throw new BusinessRuleException(
                    "Estoque insuficiente! Ingressos disponiveis: " + this.availableTickets
                            + ", solicitados: " + quantity);
        }
        this.availableTickets -= quantity;
    }

    public void updateDetails(
            String title,
            String description,
            EventCategory category,
            LocalDateTime eventDate,
            int totalCapacity,
            BigDecimal basePrice,
            BigDecimal platformFee) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.eventDate = eventDate;
        this.totalCapacity = totalCapacity;
        this.availableTickets = totalCapacity;
        this.basePrice = basePrice;
        this.platformFee = platformFee;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public EventCategory getCategory() {
        return category;
    }

    public LocalDateTime getEventDate() {
        return eventDate;
    }

    public int getTotalCapacity() {
        return totalCapacity;
    }

    public int getAvailableTickets() {
        return availableTickets;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public BigDecimal getPlatformFee() {
        return platformFee;
    }

    public Long getVersion() {
        return version;
    }
}
