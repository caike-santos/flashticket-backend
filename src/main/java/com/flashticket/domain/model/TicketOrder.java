package com.flashticket.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class TicketOrder {

    private final UUID id;
    private final UUID eventId;
    private final String customerEmail;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal totalAmount;
    private OrderStatus status;
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;

    public TicketOrder(
            UUID id,
            UUID eventId,
            String customerEmail,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal totalAmount,
            OrderStatus status,
            LocalDateTime createdAt,
            LocalDateTime expiresAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.eventId = eventId;
        this.customerEmail = customerEmail;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = totalAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
