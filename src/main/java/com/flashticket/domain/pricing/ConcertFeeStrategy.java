package com.flashticket.domain.pricing;

import com.flashticket.domain.model.EventCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class ConcertFeeStrategy implements FeeCalculationStrategy {

    private static final BigDecimal CONCERT_FEE_RATE = new BigDecimal("0.15"); // 15%

    @Override
    public boolean supports(EventCategory category) {
        return EventCategory.CONCERT == category;
    }

    @Override
    public BigDecimal calculateFee(BigDecimal basePrice) {
        return basePrice.multiply(CONCERT_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }
}
