package com.flashticket.domain.pricing;

import com.flashticket.domain.model.EventCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class WorkshopFeeStrategy implements FeeCalculationStrategy {

    private static final BigDecimal WORKSHOP_FEE_RATE = new BigDecimal("0.05"); // 5% (incentivo educacional)

    @Override
    public boolean supports(EventCategory category) {
        return EventCategory.WORKSHOP == category;
    }

    @Override
    public BigDecimal calculateFee(BigDecimal basePrice) {
        return basePrice.multiply(WORKSHOP_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }
}
