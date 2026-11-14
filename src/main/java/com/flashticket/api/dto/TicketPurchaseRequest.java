package com.flashticket.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TicketPurchaseRequest(
        @NotNull(message = "O ID do evento e obrigatorio")
        UUID eventId,

        @NotBlank(message = "O e-mail do cliente e obrigatorio")
        @Email(message = "O e-mail informado e invalido")
        String customerEmail,

        @Min(value = 1, message = "A quantidade deve ser de pelo menos 1 ingresso")
        int quantity,

        boolean usePessimisticLock,

        boolean simulateFailureAfterStockDeduction
) {
}
