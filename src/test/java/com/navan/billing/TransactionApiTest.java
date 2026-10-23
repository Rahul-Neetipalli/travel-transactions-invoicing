package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.support.Api.Resp;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import org.junit.jupiter.api.Test;

/** Brief tests 1 and 2, plus the validation contract. */
class TransactionApiTest extends ApiTestBase {

    /** Brief test 1: create every seed type; GET returns coupons, hotel address, rail legs and fee_kind, not an empty object. */
    @Test
    void createsEveryTypeAndGetReturnsTheTypeSpecificStructure() {
        String flightId = create("flight");
        String hotelId = create("hotel");
        String railId = create("rail");
        String tripFeeId = create("trip_fee");
        String agentFeeId = create("agent_call_fee");

        JsonNode flight = api.get("/transactions/" + flightId).body();
        assertThat(flight.get("type").asText()).isEqualTo("flight");
        assertThat(flight.get("metadata").get("origin").asText()).isEqualTo("TXL");
        assertThat(flight.get("metadata").get("coupons")).hasSize(1);
        assertThat(flight.get("metadata").get("coupons").get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertMoney(flight.get("metadata").get("fare"), "180");
        assertThat(flight.get("tax_lines").get(0).get("name").asText()).isEqualTo("Airport tax");
        assertMoney(flight.get("tax_lines").get(0).get("amount"), "15");
        assertThat(flight.get("fee_lines").get(0).get("name").asText()).isEqualTo("YQ");

        JsonNode hotel = api.get("/transactions/" + hotelId).body();
        assertThat(hotel.get("metadata").get("name").asText()).isEqualTo("Hotel Mitte London");
        assertThat(hotel.get("metadata").get("address").get("street").asText()).isEqualTo("12 Kingsway");
        assertThat(hotel.get("metadata").get("address").get("city").asText()).isEqualTo("London");
        assertThat(hotel.get("metadata").get("address").get("country").asText()).isEqualTo("GB");
        assertThat(hotel.get("metadata").get("nights").asInt()).isEqualTo(2);
        assertThat(hotel.get("metadata").get("extras").get(0).get("name").asText()).isEqualTo("Breakfast");
        assertThat(hotel.get("tax_lines").get(0).get("name").asText()).isEqualTo("VAT");
        assertMoney(hotel.get("tax_lines").get(0).get("rate"), "0.07");
        assertThat(hotel.get("fee_lines")).isEmpty();

        JsonNode rail = api.get("/transactions/" + railId).body();
        assertThat(rail.get("metadata").get("legs")).hasSize(2);
        assertThat(rail.get("metadata").get("legs").get(0).get("destination").asText()).isEqualTo("Lille Europe");
        assertThat(rail.get("metadata").get("legs").get(1).get("origin").asText()).isEqualTo("Lille Europe");
        assertThat(rail.get("metadata").get("legs").get(1).get("train_number").asText()).isEqualTo("EST9122");
        assertThat(rail.get("fee_lines").get(0).get("name").asText()).isEqualTo("Booking fee");

        JsonNode tripFee = api.get("/transactions/" + tripFeeId).body();
        assertThat(tripFee.get("type").asText()).isEqualTo("navan_fee");
        assertThat(tripFee.get("metadata").get("fee_kind").asText()).isEqualTo("trip_fee");
        assertThat(tripFee.get("metadata").get("related_external_id").asText()).isEqualTo("flight-TXL-LHR-2026-03-11");
        JsonNode agentFee = api.get("/transactions/" + agentFeeId).body();
        assertThat(agentFee.get("metadata").get("fee_kind").asText()).isEqualTo("agent_call_fee");
    }

    /** A create answers 201 with a Location header, a txn_ULID id, and money printed at the currency scale. */
    @Test
    void createReturns201WithLocationAndServerIdAndMoneyAtTheCurrencyScale() {
        Resp created = api.post("/transactions", Fixtures.seed("flight"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.id()).matches("txn_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(created.location()).isEqualTo("/transactions/" + created.id());
        assertThat(created.raw()).contains("\"total\":220.00");
        assertThat(created.body().get("external_id").asText()).isEqualTo("flight-TXL-LHR-2026-03-11");
    }

    /** Brief test 2: the duplicate_flight fixture as shipped is a 409 carrying the existing id; the original row is untouched. */
    @Test
    void duplicateExternalIdIsRejectedAndTheFixtureBodyReturnsTheExistingId() {
        Resp first = api.post("/transactions", Fixtures.seed("flight"));

        // the fixture as shipped: same external_id, empty coupons, and a stray "comment" field
        Resp duplicate = api.post("/transactions", Fixtures.seed("duplicate_flight"));

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.contentType()).startsWith("application/problem+json");
        assertThat(duplicate.body().get("code").asText()).isEqualTo("DUPLICATE_EXTERNAL_ID");
        assertThat(duplicate.body().get("id").asText()).isEqualTo(first.id());
        assertThat(duplicate.body().get("existing_id").asText()).isEqualTo(first.id());
        assertThat(duplicate.location()).isEqualTo("/transactions/" + first.id());
        assertThat(rowCount("transactions")).isEqualTo(1);
        assertThat(rowCount("transaction_tax_lines")).isEqualTo(1);
        // the original was not touched by the second body
        assertThat(api.get("/transactions/" + first.id()).body().get("metadata").get("coupons")).hasSize(1);
    }

    /** Brief test 2: posting an identical body twice leaves exactly one row. */
    @Test
    void postingTheSameBodyTwiceStoresExactlyOneRow() {
        Resp first = api.post("/transactions", Fixtures.seed("hotel"));
        Resp second = api.post("/transactions", Fixtures.seed("hotel"));

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().get("id").asText()).isEqualTo(first.id());
        Resp listed = api.get("/transactions?external_id=hotel-LON-2026-03-11");
        assertThat(listed.body().get("items")).hasSize(1);
        assertThat(rowCount("transactions")).isEqualTo(1);
    }

    /** Extra fields inside metadata are allowed and round-trip unchanged. */
    @Test
    void unknownMetadataFieldsAreStoredAndReturned() {
        ObjectNode body = Fixtures.seed("flight");
        ((ObjectNode) body.get("metadata")).put("seat_class", "economy");
        ((ObjectNode) body.get("metadata").get("coupons").get(0)).put("booking_class", "Y");

        String id = api.post("/transactions", body).id();

        JsonNode stored = api.get("/transactions/" + id).body();
        assertThat(stored.get("metadata").get("seat_class").asText()).isEqualTo("economy");
        assertThat(stored.get("metadata").get("coupons").get(0).get("booking_class").asText()).isEqualTo("Y");
    }

    /** An unknown type is a 400 pointing at /type, and nothing is stored. */
    @Test
    void unknownTypeIsA400() {
        ObjectNode body = Fixtures.seed("flight");
        body.put("type", "car_rental");

        Resp response = api.post("/transactions", body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body().get("errors").get(0).get("pointer").asText()).isEqualTo("/type");
        assertThat(rowCount("transactions")).isZero();
    }

    /** An unknown navan fee_kind is a 400 pointing at /metadata/fee_kind. */
    @Test
    void unknownFeeKindIsA400() {
        ObjectNode body = Fixtures.seed("trip_fee");
        ((ObjectNode) body.get("metadata")).put("fee_kind", "coffee_fee");

        Resp response = api.post("/transactions", body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().toString()).contains("/metadata/fee_kind");
    }

    /** A flight without coupons, hotel without address, rail without legs or an empty metadata object is a 400. */
    @Test
    void missingTypeSpecificMetadataIsA400AndNothingIsStored() {
        ObjectNode flight = Fixtures.seed("flight", "flight-no-coupons");
        ((ObjectNode) flight.get("metadata")).remove("coupons");
        ObjectNode hotel = Fixtures.seed("hotel", "hotel-no-address");
        ((ObjectNode) hotel.get("metadata")).remove("address");
        ObjectNode rail = Fixtures.seed("rail", "rail-no-legs");
        ((ObjectNode) rail.get("metadata")).remove("legs");
        ObjectNode empty = Fixtures.seed("flight", "flight-empty-metadata");
        empty.set("metadata", Fixtures.seed("flight").objectNode());

        for (ObjectNode body : new ObjectNode[]{flight, hotel, rail, empty}) {
            Resp response = api.post("/transactions", body);
            assertThat(response.status()).as(body.get("external_id").asText()).isEqualTo(400);
        }
        assertThat(rowCount("transactions")).isZero();
    }

    /** Metadata must match the type: a flight's metadata on a hotel is a 400. */
    @Test
    void aFlightMetadataBlobOnAHotelIsRejected() {
        ObjectNode body = Fixtures.seed("hotel", "hotel-with-flight-blob");
        body.set("metadata", Fixtures.seed("flight").get("metadata"));

        assertThat(api.post("/transactions", body).status()).isEqualTo(400);
    }

    /** Several problems in one body are all reported in one 400. */
    @Test
    void allValidationErrorsAreReportedAtOnce() {
        ObjectNode body = Fixtures.seed("flight", "flight-bad");
        body.remove("currency");
        body.remove("occurred_at");
        body.put("total", "lots");

        Resp response = api.post("/transactions", body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("errors").size()).isGreaterThanOrEqualTo(3);
    }

    /** 10.005 EUR is a 400; we never round money. */
    @Test
    void moneyBeyondTheCurrencyScaleIsRejectedNotRounded() {
        ObjectNode body = Fixtures.seed("flight", "flight-3dp");
        body.put("total", new java.math.BigDecimal("10.005"));

        Resp response = api.post("/transactions", body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().toString()).contains("/total").contains("decimal places");
    }

    /** Lowercase, short, long and made-up currency codes are a 400. */
    @Test
    void currencyMustBeARealUppercaseIsoCode() {
        for (String bad : new String[]{"eur", "EU", "XXXX", "ZZZ"}) {
            ObjectNode body = Fixtures.seed("flight", "flight-cur-" + bad);
            body.put("currency", bad);
            assertThat(api.post("/transactions", body).status()).as(bad).isEqualTo(400);
        }
    }

    /** occurred_at without an offset is ambiguous and is a 400. */
    @Test
    void timestampWithoutAnOffsetIsRejected() {
        ObjectNode body = Fixtures.seed("flight", "flight-no-offset");
        body.put("occurred_at", "2026-03-11T06:40:00");

        assertThat(api.post("/transactions", body).status()).isEqualTo(400);
    }

    /** A body without external_id, or that is not valid JSON (including valid JSON followed by junk), is a 400. */
    @Test
    void missingExternalIdAndMalformedJsonAreA400() {
        ObjectNode body = Fixtures.seed("flight");
        body.remove("external_id");

        assertThat(api.post("/transactions", body).status()).isEqualTo(400);
        assertThat(api.post("/transactions", "{not json").status()).isEqualTo(400);
        // a valid body followed by junk is malformed JSON, not "the valid part"
        String validThenJunk = com.navan.billing.support.Fixtures.seed("flight", "trailing-junk").toString() + " xx";
        Resp junk = api.post("/transactions", validThenJunk);
        assertThat(junk.status()).isEqualTo(400);
        assertThat(junk.body().get("code").asText()).isEqualTo("INVALID_JSON");
    }

    /** Unknown, malformed and wrong-prefix transaction ids are 404. */
    @Test
    void unknownAndMalformedIdsAre404() {
        assertThat(api.get("/transactions/txn_00000000000000000000000000").status()).isEqualTo(404);
        assertThat(api.get("/transactions/txn_abc").status()).isEqualTo(404);
        assertThat(api.get("/transactions/not-an-id").status()).isEqualTo(404);
        assertThat(api.get("/transactions/inv_00000000000000000000000000").status()).isEqualTo(404);
    }

    /** GET /health reports the service and its database as UP. */
    @Test
    void healthReportsTheDatabase() {
        Resp health = api.get("/health");

        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body().get("database").asText()).isEqualTo("UP");
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
