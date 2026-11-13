package com.flashticket.domain.pricing;

import com.flashticket.domain.model.EventCategory;
import java.math.BigDecimal;

/**
 * TÓPICO: SOLID
 * - OCP (Open/Closed Principle): Novas categorias de evento podem ter novas regras de taxa
 *   apenas criando uma classe que implemente esta interface, sem modificar o serviço principal.
 * - ISP (Interface Segregation Principle): Interface enxuta e focada apenas no cálculo de taxa.
 * - LSP (Liskov Substitution Principle): Qualquer implementação deve retornar um valor >= 0
 *   com 2 casas decimais.
 */
public interface FeeCalculationStrategy {

    boolean supports(EventCategory category);

    BigDecimal calculateFee(BigDecimal basePrice);
}
