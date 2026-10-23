package com.navan.billing.transaction.metadata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Hotel stay: property name and address, dates, nightly rate and optional extras. */
public record HotelMetadata(
        String name, Address address, LocalDate checkIn, LocalDate checkOut, int nights,
        BigDecimal roomRatePerNight, List<Extra> extras) implements Metadata {

    public record Address(String street, String city, String country) {
    }

    public record Extra(String name, BigDecimal amount) {
    }
}
