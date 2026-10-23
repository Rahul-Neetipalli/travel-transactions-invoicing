package com.navan.billing.transaction.metadata;

import java.math.BigDecimal;
import java.util.List;

/** Flight: origin/destination, fare, and one coupon per flown segment (a round trip has two or more). */
public record FlightMetadata(String origin, String destination, BigDecimal fare, List<Coupon> coupons) implements Metadata {

    public record Coupon(String flightNumber, String carrier, String from, String to, String departure, String arrival) {
    }
}
