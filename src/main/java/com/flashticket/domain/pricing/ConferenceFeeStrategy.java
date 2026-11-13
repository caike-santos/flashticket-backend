package com.flashticket.domain.pricing;

import com.flashticket.domain.model.EventCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class ConferenceFeeStrategy implements FeeCalculationStrategy {

    private static final BigDecimal CONFERENCE_FEE_RATE = new BigDecimal("0.10"); // 10%

    @Override
    public boolean supports(EventCategory category) {
        return EventCategory.CONFERENCE == category;
    }

    @Override
    public BigDecimal calculateFee(BigDecimal basePrice) {
        return basePrice.multiply(CONFERENCE_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }
}
