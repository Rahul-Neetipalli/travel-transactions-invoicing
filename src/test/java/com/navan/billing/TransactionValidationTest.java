package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.support.Api.Resp;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Field-level validation of POST /transactions. Each rejected case breaks exactly one rule in an otherwise
 * valid fixture and checks that the 400 points at the offending field. The accepted cases pin down what
 * is deliberately allowed.
 */
class TransactionValidationTest extends ApiTestBase {

    /** One broken rule per case: the 400 must name the field (as a JSON pointer) and nothing is stored. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void rejectsAnInvalidFieldAndPointsAtIt(String scenario, String seed, Consumer<ObjectNode> breakIt, String pointer) {
        ObjectNode body = Fixtures.seed(seed, "validation-" + seed);
        breakIt.accept(body);

        Resp response = api.post("/transactions", body);

        assertThat(response.status()).as(response.raw()).isEqualTo(400);
        assertThat(response.body().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(pointers(response)).as(response.raw()).contains(pointer);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                // shared envelope
                Arguments.of("blank external_id", "flight", set("external_id", ""), "/external_id"),
                Arguments.of("external_id longer than 128 chars", "flight", set("external_id", "x".repeat(129)), "/external_id"),
                Arguments.of("missing tax_lines list", "flight", remove("tax_lines"), "/tax_lines"),
                Arguments.of("fee_lines is not a list", "flight", set("fee_lines", "YQ"), "/fee_lines"),
                Arguments.of("tax line without a name", "flight", at("tax_lines", 0, l -> l.remove("name")), "/tax_lines/0/name"),
                Arguments.of("tax amount is a string", "flight", at("tax_lines", 0, l -> l.put("amount", "15")), "/tax_lines/0/amount"),
                Arguments.of("tax rate with more than 6 decimals", "hotel", at("tax_lines", 0, l -> l.put("rate", new BigDecimal("0.0712345"))), "/tax_lines/0/rate"),
                Arguments.of("more than 100 tax lines", "flight", tooManyTaxLines(), "/tax_lines"),
                Arguments.of("metadata is an array", "flight", (Consumer<ObjectNode>) b -> b.putArray("metadata"), "/metadata"),
                Arguments.of("metadata larger than 64 KB", "flight", meta(m -> m.put("notes", "x".repeat(70_000))), "/metadata"),
                // flight
                Arguments.of("flight without origin", "flight", meta(m -> m.remove("origin")), "/metadata/origin"),
                Arguments.of("flight fare with 3 decimals in EUR", "flight", meta(m -> m.put("fare", new BigDecimal("180.001"))), "/metadata/fare"),
                Arguments.of("flight with an empty coupon list", "flight", meta(m -> m.putArray("coupons")), "/metadata/coupons"),
                Arguments.of("coupon without flight_number", "flight", coupon(c -> c.remove("flight_number")), "/metadata/coupons/0/flight_number"),
                Arguments.of("coupon departure is not a timestamp", "flight", coupon(c -> c.put("departure", "tomorrow")), "/metadata/coupons/0/departure"),
                // hotel
                Arguments.of("hotel without a name", "hotel", meta(m -> m.remove("name")), "/metadata/name"),
                Arguments.of("hotel address without a city", "hotel", meta(m -> ((ObjectNode) m.get("address")).remove("city")), "/metadata/address/city"),
                Arguments.of("hotel check_in is not a date", "hotel", meta(m -> m.put("check_in", "11/03/2026")), "/metadata/check_in"),
                Arguments.of("hotel with negative nights", "hotel", meta(m -> m.put("nights", -1)), "/metadata/nights"),
                Arguments.of("hotel extra without an amount", "hotel", meta(m -> ((ObjectNode) m.get("extras").get(0)).remove("amount")), "/metadata/extras/0/amount"),
                // rail
                Arguments.of("rail with no legs", "rail", meta(m -> m.putArray("legs")), "/metadata/legs"),
                Arguments.of("rail leg without an origin", "rail", meta(m -> ((ObjectNode) m.get("legs").get(1)).remove("origin")), "/metadata/legs/1/origin"),
                // navan fee
                Arguments.of("navan fee without fee_kind", "trip_fee", meta(m -> m.remove("fee_kind")), "/metadata/fee_kind"));
    }

    /** A refund or credit note can be negative; the upstream owns that decision. */
    @Test
    void acceptsNegativeAmounts() {
        ObjectNode body = Fixtures.seed("trip_fee", "refund-1");
        body.put("total", new BigDecimal("-25.00"));

        Resp response = api.post("/transactions", body);

        assertThat(response.status()).isEqualTo(201);
        assertMoney(response.body().get("total"), "-25");
    }

    /** The currency decides the scale: JPY has no minor unit, KWD has three. */
    @Test
    void usesTheCurrencysOwnDecimalPlaces() {
        ObjectNode yen = Fixtures.seed("trip_fee", "fee-jpy");
        yen.put("currency", "JPY");
        yen.put("total", 2500);
        ObjectNode dinar = Fixtures.seed("trip_fee", "fee-kwd");
        dinar.put("currency", "KWD");
        dinar.put("total", new BigDecimal("7.125"));
        ObjectNode badYen = Fixtures.seed("trip_fee", "fee-jpy-bad");
        badYen.put("currency", "JPY");
        badYen.put("total", new BigDecimal("2500.5"));

        assertThat(api.post("/transactions", yen).raw()).contains("\"total\":2500,");
        assertThat(api.post("/transactions", dinar).raw()).contains("\"total\":7.125,");
        assertThat(api.post("/transactions", badYen).status()).isEqualTo(400);
    }

    /** Timestamps with any offset are accepted and returned in UTC. */
    @Test
    void normalisesOccurredAtToUtc() {
        ObjectNode body = Fixtures.seed("flight");
        body.put("occurred_at", "2026-03-11T08:40:00+02:00");

        Resp response = api.post("/transactions", body);

        assertThat(response.body().get("occurred_at").asText()).isEqualTo("2026-03-11T06:40:00Z");
    }

    /** Money inside metadata is never passed through a double: 180.0 comes back as 180.0, never 1.8E+2. */
    @Test
    void keepsMetadataNumbersExactly() {
        ObjectNode body = Fixtures.seed("hotel");
        ((ObjectNode) body.get("metadata")).put("room_rate_per_night", new BigDecimal("140.10"));

        String raw = api.get("/transactions/" + api.post("/transactions", body).id()).raw();

        assertThat(raw).contains("\"room_rate_per_night\":140.10").doesNotContain("E+");
    }

    /** Optional fields (carrier, train_number, class, extras, description, related_external_id) may be left out. */
    @Test
    void acceptsBodiesWithoutOptionalFields() {
        ObjectNode flight = Fixtures.seed("flight");
        ((ObjectNode) flight.get("metadata").get("coupons").get(0)).remove("carrier");
        ObjectNode hotel = Fixtures.seed("hotel");
        ((ObjectNode) hotel.get("metadata")).remove("extras");
        ObjectNode rail = Fixtures.seed("rail");
        for (JsonNode leg : rail.get("metadata").get("legs")) {
            ((ObjectNode) leg).remove("train_number");
            ((ObjectNode) leg).remove("class");
        }
        ObjectNode fee = Fixtures.seed("trip_fee");
        ((ObjectNode) fee.get("metadata")).remove("description");
        ((ObjectNode) fee.get("metadata")).remove("related_external_id");

        for (ObjectNode body : new ObjectNode[]{flight, hotel, rail, fee}) {
            assertThat(api.post("/transactions", body).status()).as(body.get("type").asText()).isEqualTo(201);
        }
    }

    /** A round trip has several coupons and they come back in order. */
    @Test
    void keepsEveryCouponOfARoundTripInOrder() {
        ObjectNode body = Fixtures.seed("flight", "round-trip");
        ObjectNode back = ((ObjectNode) body.get("metadata").get("coupons").get(0)).deepCopy();
        back.put("flight_number", "BA983").put("from", "LHR").put("to", "TXL");
        ((ArrayNode) body.get("metadata").get("coupons")).add(back);

        JsonNode coupons = api.get("/transactions/" + api.post("/transactions", body).id()).body().get("metadata").get("coupons");

        assertThat(coupons).hasSize(2);
        assertThat(coupons.get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertThat(coupons.get(1).get("flight_number").asText()).isEqualTo("BA983");
    }

    /** Only JSON is accepted; anything else is a 415 problem, not a 500. */
    @Test
    void rejectsNonJsonContentTypes() {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/transactions"))
                .header("Content-Type", "text/plain")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("hello")).build();

        int status = send(request);

        assertThat(status).isEqualTo(415);
    }

    /** Unknown routes and wrong methods answer as problem+json too. */
    @Test
    void answersUnknownRoutesAndMethodsAsProblems() {
        Resp unknown = api.get("/nope");
        Resp wrongMethod = api.post("/health", "{}");

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.contentType()).startsWith("application/problem+json");
        assertThat(wrongMethod.status()).isEqualTo(405);
    }

    // ---- helpers that break one thing in a fixture ----

    private static Consumer<ObjectNode> set(String field, String value) {
        return b -> b.put(field, value);
    }

    private static Consumer<ObjectNode> remove(String field) {
        return b -> b.remove(field);
    }

    private static Consumer<ObjectNode> meta(Consumer<ObjectNode> change) {
        return b -> change.accept((ObjectNode) b.get("metadata"));
    }

    private static Consumer<ObjectNode> coupon(Consumer<ObjectNode> change) {
        return meta(m -> change.accept((ObjectNode) m.get("coupons").get(0)));
    }

    private static Consumer<ObjectNode> at(String list, int index, Consumer<ObjectNode> change) {
        return b -> change.accept((ObjectNode) b.get(list).get(index));
    }

    private static Consumer<ObjectNode> tooManyTaxLines() {
        return b -> {
            ArrayNode lines = b.putArray("tax_lines");
            for (int i = 0; i < 101; i++) {
                lines.addObject().put("name", "T" + i).put("amount", 1);
            }
        };
    }

    private static java.util.List<String> pointers(Resp response) {
        java.util.List<String> out = new java.util.ArrayList<>();
        response.body().path("errors").forEach(e -> out.add(e.get("pointer").asText()));
        return out;
    }

    private static int send(java.net.http.HttpRequest request) {
        try {
            return java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
