package com.flashticket.domain.pricing;

import com.flashticket.domain.model.EventCategory;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * TÓPICO: SOLID (SRP, OCP, DIP)
 * O Spring injeta automaticamente todas as implementações de FeeCalculationStrategy na lista.
 * Não há if/else acoplado às categorias concretas.
 */
@Service
public class FeeCalculatorService {

    private final List<FeeCalculationStrategy> strategies;

    public FeeCalculatorService(List<FeeCalculationStrategy> strategies) {
        this.strategies = strategies;
    }

    public BigDecimal calculatePlatformFee(EventCategory category, BigDecimal basePrice) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(category))
                .findFirst()
                .map(strategy -> strategy.calculateFee(basePrice))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nenhuma estrategia de calculo de taxa encontrada para a categoria: " + category));
    }
}
