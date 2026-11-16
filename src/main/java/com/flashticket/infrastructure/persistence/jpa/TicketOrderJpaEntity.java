package com.flashticket.infrastructure.persistence.jpa;

import com.flashticket.domain.model.OrderStatus;
import com.flashticket.domain.model.TicketOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ticket_orders")
public class TicketOrderJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "customer_email", nullable = false, length = 255)
    private String customerEmail;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected TicketOrderJpaEntity() {
    }

    public TicketOrderJpaEntity(
            UUID id,
            UUID eventId,
            String customerEmail,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal totalAmount,
            OrderStatus status,
            LocalDateTime createdAt,
            LocalDateTime expiresAt) {
        this.id = id;
        this.eventId = eventId;
        this.customerEmail = customerEmail;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = totalAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static TicketOrderJpaEntity fromDomain(TicketOrder order) {
        return new TicketOrderJpaEntity(
                order.getId(),
                order.getEventId(),
                order.getCustomerEmail(),
                order.getQuantity(),
                order.getUnitPrice(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getExpiresAt()
        );
    }

    public TicketOrder toDomain() {
        return new TicketOrder(
                this.id,
                this.eventId,
                this.customerEmail,
                this.quantity,
                this.unitPrice,
                this.totalAmount,
                this.status,
                this.createdAt,
                this.expiresAt
        );
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

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
