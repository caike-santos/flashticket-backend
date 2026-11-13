package com.flashticket.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class Event {

    private final UUID id;
    private String title;
    private String description;
    private EventCategory category;
    private LocalDateTime eventDate;
    private int totalCapacity;
    private int availableTickets;
    private BigDecimal basePrice;
    private BigDecimal platformFee;

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
        this.id = id != null ? id : UUID.randomUUID();
        this.title = title;
        this.description = description;
        this.category = category;
        this.eventDate = eventDate;
        this.totalCapacity = totalCapacity;
        this.availableTickets = availableTickets;
        this.basePrice = basePrice;
        this.platformFee = platformFee;
    }

    public BigDecimal getFinalPrice() {
        return basePrice.add(platformFee);
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
}
