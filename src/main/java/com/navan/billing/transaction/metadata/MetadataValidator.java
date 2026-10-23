package com.navan.billing.transaction.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.common.JsonReader;
import com.navan.billing.common.error.Violations;
import com.navan.billing.transaction.TransactionType;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Binds metadata to the typed record for its transaction type and reports every problem with a JSON pointer.
 * Validation only: the caller persists the original tree, so unknown fields survive a round trip.
 */
@Component
public class MetadataValidator {

    private static final int TEXT = 256;
    private static final int MAX_ITEMS = 100;

    public Metadata validate(TransactionType type, JsonNode node, Currency currency, Violations violations, String pointer) {
        if (node == null || !node.isObject()) {
            violations.add(pointer, node == null || node.isNull() ? "is required" : "must be an object");
            return null;
        }
        JsonReader reader = new JsonReader(violations);
        return switch (type) {
            case FLIGHT -> flight(node, currency, reader, pointer);
            case HOTEL -> hotel(node, currency, reader, pointer);
            case RAIL -> rail(node, reader, pointer);
            case NAVAN_FEE -> navanFee(node, reader, violations, pointer);
        };
    }

    private Metadata flight(JsonNode metadata, Currency currency, JsonReader reader, String pointer) {
        String origin = reader.text(metadata, "origin", pointer, true, TEXT);
        String destination = reader.text(metadata, "destination", pointer, true, TEXT);
        var fare = reader.money(metadata, "fare", pointer, true, currency);
        List<FlightMetadata.Coupon> coupons = new ArrayList<>();
        ArrayNode array = reader.array(metadata, "coupons", pointer, true, true, MAX_ITEMS);
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                String at = pointer + "/coupons/" + i;
                ObjectNode coupon = reader.objectAt(array, i, pointer + "/coupons");
                if (coupon == null) {
                    continue;
                }
                coupons.add(new FlightMetadata.Coupon(
                        reader.text(coupon, "flight_number", at, true, 16),
                        reader.text(coupon, "carrier", at, false, 16),
                        reader.text(coupon, "from", at, true, TEXT),
                        reader.text(coupon, "to", at, true, TEXT),
                        reader.timestamp(coupon, "departure", at, true),
                        reader.timestamp(coupon, "arrival", at, true)));
            }
        }
        return new FlightMetadata(origin, destination, fare, coupons);
    }

    private Metadata hotel(JsonNode metadata, Currency currency, JsonReader reader, String pointer) {
        String name = reader.text(metadata, "name", pointer, true, TEXT);
        HotelMetadata.Address address = null;
        ObjectNode addressNode = reader.object(metadata, "address", pointer, true);
        if (addressNode != null) {
            address = new HotelMetadata.Address(
                    reader.text(addressNode, "street", pointer + "/address", true, TEXT),
                    reader.text(addressNode, "city", pointer + "/address", true, TEXT),
                    reader.text(addressNode, "country", pointer + "/address", true, TEXT));
        }
        var checkIn = reader.date(metadata, "check_in", pointer, true);
        var checkOut = reader.date(metadata, "check_out", pointer, true);
        Integer nights = reader.wholeNumber(metadata, "nights", pointer, true);
        var roomRate = reader.money(metadata, "room_rate_per_night", pointer, true, currency);
        List<HotelMetadata.Extra> extras = new ArrayList<>();
        ArrayNode array = reader.array(metadata, "extras", pointer, false, false, MAX_ITEMS);
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                String at = pointer + "/extras/" + i;
                ObjectNode extra = reader.objectAt(array, i, pointer + "/extras");
                if (extra != null) {
                    extras.add(new HotelMetadata.Extra(reader.text(extra, "name", at, true, TEXT), reader.money(extra, "amount", at, true, currency)));
                }
            }
        }
        return new HotelMetadata(name, address, checkIn, checkOut, nights == null ? 0 : nights, roomRate, extras);
    }

    private Metadata rail(JsonNode metadata, JsonReader reader, String pointer) {
        List<RailMetadata.Leg> legs = new ArrayList<>();
        ArrayNode array = reader.array(metadata, "legs", pointer, true, true, MAX_ITEMS);
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                String at = pointer + "/legs/" + i;
                ObjectNode leg = reader.objectAt(array, i, pointer + "/legs");
                if (leg == null) {
                    continue;
                }
                legs.add(new RailMetadata.Leg(
                        reader.text(leg, "origin", at, true, TEXT),
                        reader.text(leg, "destination", at, true, TEXT),
                        reader.timestamp(leg, "departure", at, true),
                        reader.timestamp(leg, "arrival", at, true),
                        reader.text(leg, "train_number", at, false, 32),
                        reader.text(leg, "class", at, false, 32)));
            }
        }
        return new RailMetadata(legs);
    }

    private Metadata navanFee(JsonNode metadata, JsonReader reader, Violations violations, String pointer) {
        String kindText = reader.text(metadata, "fee_kind", pointer, true, 32);
        FeeKind kind = null;
        if (kindText != null) {
            kind = FeeKind.fromWire(kindText).orElse(null);
            if (kind == null) {
                violations.add(pointer + "/fee_kind", "must be one of trip_fee, agent_call_fee");
            }
        }
        String description = reader.text(metadata, "description", pointer, false, 1024);
        String relatedExternalId = reader.text(metadata, "related_external_id", pointer, false, 128);
        return kind == null ? null : new NavanFeeMetadata(kind, description, relatedExternalId);
    }
}
