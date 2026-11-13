package com.flashticket.api.dto;

import com.flashticket.domain.model.EventCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EventRequest(
        @NotBlank(message = "O titulo do evento e obrigatorio")
        String title,

        @NotBlank(message = "A descricao do evento e obrigatoria")
        String description,

        @NotNull(message = "A categoria do evento e obrigatoria")
        EventCategory category,

        @NotNull(message = "A data do evento e obrigatoria")
        LocalDateTime eventDate,

        @Min(value = 1, message = "A capacidade total deve ser de pelo menos 1 ingresso")
        int totalCapacity,

        @NotNull(message = "O preco base e obrigatorio")
        @DecimalMin(value = "0.00", inclusive = true, message = "O preco base nao pode ser negativo")
        BigDecimal basePrice
) {
}
