package com.flashticket.api.dto;

import com.flashticket.domain.model.OrderStatus;
import com.flashticket.domain.model.TicketOrder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record TicketOrderResponse(
        UUID id,
        UUID eventId,
        String customerEmail,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {
    public static TicketOrderResponse fromDomain(TicketOrder order) {
        return new TicketOrderResponse(
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
}
