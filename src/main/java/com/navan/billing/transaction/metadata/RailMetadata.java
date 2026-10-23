package com.navan.billing.transaction.metadata;

import java.util.List;

/** Rail journey as legs: a change of train is two legs. */
public record RailMetadata(List<Leg> legs) implements Metadata {

    public record Leg(String origin, String destination, String departure, String arrival, String trainNumber, String travelClass) {
    }
}
