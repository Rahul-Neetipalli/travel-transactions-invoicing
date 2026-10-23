package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.support.Api.Resp;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Brief tests 3 and 5, plus the invoice contract. */
class InvoiceApiTest extends ApiTestBase {

    /** Brief test 3: invoice the five trip seeds and compare every total and rollup with expected-invoice.json (685.70 EUR). */
    @Test
    void tripInvoiceMatchesTheExpectedInvoiceFixture() {
        String[] trip = createTrip();
        Resp created = api.post("/invoices", Fixtures.invoiceOf(trip));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.location()).isEqualTo("/invoices/" + created.id());

        JsonNode invoice = api.get("/invoices/" + created.id()).body();
        JsonNode expected = Fixtures.expectedInvoice();

        assertThat(invoice.get("currency").asText()).isEqualTo(expected.get("currency").asText());
        assertThat(invoice.get("transaction_count").asInt()).isGreaterThanOrEqualTo(expected.get("line_count_minimum").asInt());
        assertThat(invoice.get("transaction_ids")).hasSize(5);
        assertThat(texts(invoice.get("transaction_types_included"))).containsExactlyInAnyOrderElementsOf(texts(expected.get("transaction_types_included")));
        assertThat(texts(invoice.get("navan_fee_kinds_included"))).containsExactlyInAnyOrderElementsOf(texts(expected.get("navan_fee_kinds_included")));

        JsonNode expectedTotals = expected.get("totals");
        expectedTotals.fieldNames().forEachRemaining(key ->
                assertMoney(invoice.get("totals").get(key), expectedTotals.get(key).decimalValue().toPlainString()));
        assertMoney(invoice.get("totals").get("grand_total"), "685.70");
        assertThat(invoice.get("totals").get("grand_total").decimalValue().toPlainString()).isEqualTo("685.70");

        for (JsonNode expectedTax : expected.get("tax_rollup_example")) {
            JsonNode actual = find(invoice.get("tax_rollup"), expectedTax.get("name").asText());
            assertMoney(actual.get("amount"), expectedTax.get("amount").decimalValue().toPlainString());
            if (expectedTax.has("rate")) {
                assertMoney(actual.get("rate"), expectedTax.get("rate").decimalValue().toPlainString());
            }
        }
        for (JsonNode expectedFee : expected.get("fee_rollup_example")) {
            assertMoney(find(invoice.get("fee_rollup"), expectedFee.get("name").asText()).get("amount"),
                    expectedFee.get("amount").decimalValue().toPlainString());
        }
    }

    /** The invoice holds its own copy of each transaction: type, lines and the full metadata. */
    @Test
    void invoiceCarriesFullSnapshotsOfEveryTransaction() {
        String[] trip = createTrip();
        String invoiceId = createInvoice(trip);

        JsonNode snapshots = api.get("/invoices/" + invoiceId).body().get("transactions");

        assertThat(snapshots).hasSize(5);
        JsonNode flight = snapshots.get(0);
        assertThat(flight.get("transaction_id").asText()).isEqualTo(trip[0]);
        assertThat(flight.get("type").asText()).isEqualTo("flight");
        assertThat(flight.get("metadata").get("coupons").get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertThat(flight.get("tax_lines").get(0).get("name").asText()).isEqualTo("Airport tax");
        assertThat(flight.get("fee_lines").get(0).get("name").asText()).isEqualTo("YQ");
        assertThat(snapshots.get(1).get("metadata").get("address").get("city").asText()).isEqualTo("London");
        assertThat(snapshots.get(2).get("metadata").get("legs")).hasSize(2);
        assertThat(snapshots.get(4).get("metadata").get("fee_kind").asText()).isEqualTo("agent_call_fee");
    }

    /** The {"transaction_id": ...} body shape works, and totals only list the types present. */
    @Test
    void aSingleTransactionIdCreatesAnInvoice() {
        String flightId = create("flight");

        Resp created = api.post("/invoices", java.util.Map.of("transaction_id", flightId));

        assertThat(created.status()).isEqualTo(201);
        JsonNode invoice = api.get("/invoices/" + created.id()).body();
        assertMoney(invoice.get("totals").get("grand_total"), "220.00");
        assertMoney(invoice.get("totals").get("flight"), "220.00");
        assertThat(invoice.get("totals").has("hotel")).isFalse();
        assertThat(invoice.get("transaction_count").asInt()).isEqualTo(1);
    }

    /** One transaction may be billed on several invoices (it is frozen by the first). */
    @Test
    void theSameTransactionCanBeOnMoreThanOneInvoice() {
        String flightId = create("flight");

        String first = createInvoice(flightId);
        String second = createInvoice(flightId);

        assertThat(first).isNotEqualTo(second);
        assertThat(api.get("/invoices").body().get("items")).hasSize(2);
    }

    /** Brief test 5: EUR + USD on one invoice is a 409 that says which ids are in which currency; nothing is created. */
    @Test
    void invoicesOfEurAndUsdTransactionsAreRejectedWith409() {
        String eur = create("flight");
        ObjectNode usdBody = Fixtures.seed("trip_fee", "navan-trip-fee-usd");
        usdBody.put("currency", "USD");
        String usd = api.post("/transactions", usdBody).id();

        Resp response = api.post("/invoices", Fixtures.invoiceOf(eur, usd));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("code").asText()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(response.body().get("currencies").get("EUR").get(0).asText()).isEqualTo(eur);
        assertThat(response.body().get("currencies").get("USD").get(0).asText()).isEqualTo(usd);
        assertThat(api.get("/invoices").body().get("items")).isEmpty();
    }

    /** Unknown ids are a 404 listing every missing id, not just the first; nothing is created. */
    @Test
    void missingTransactionsAre404AndAllMissingIdsAreListed() {
        String flightId = create("flight");
        String ghostOne = "txn_00000000000000000000000001";
        String ghostTwo = "txn_00000000000000000000000002";

        Resp response = api.post("/invoices", Fixtures.invoiceOf(flightId, ghostOne, ghostTwo));

        assertThat(response.status()).isEqualTo(404);
        assertThat(texts(response.body().get("missing_ids"))).containsExactly(ghostOne, ghostTwo);
        assertThat(api.get("/invoices").body().get("items")).isEmpty();
    }

    /** The same id twice in one request is a 409 (we do not silently de-duplicate). */
    @Test
    void theSameTransactionIdTwiceInOneRequestIs409() {
        String flightId = create("flight");

        Resp response = api.post("/invoices", Fixtures.invoiceOf(flightId, flightId));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("code").asText()).isEqualTo("DUPLICATE_TRANSACTION_ID");
        assertThat(texts(response.body().get("duplicate_ids"))).containsExactly(flightId);
        assertThat(api.get("/invoices").body().get("items")).isEmpty();
    }

    /** Malformed invoice bodies: empty, empty list, both shapes at once, more than 100 ids, a non-string id. */
    @Test
    void badInvoiceRequestsAre400() {
        String flightId = create("flight");
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(String.format("txn_%026d", i).replace('0', '1'));
        }

        assertThat(api.post("/invoices", "{}").status()).isEqualTo(400);
        assertThat(api.post("/invoices", java.util.Map.of("transaction_ids", List.of())).status()).isEqualTo(400);
        assertThat(api.post("/invoices", java.util.Map.of("transaction_id", flightId, "transaction_ids", List.of(flightId))).status()).isEqualTo(400);
        assertThat(api.post("/invoices", java.util.Map.of("transaction_ids", tooMany)).status()).isEqualTo(400);
        assertThat(api.post("/invoices", java.util.Map.of("transaction_id", 42)).status()).isEqualTo(400);
    }

    /** GET, PATCH and DELETE of an unknown or malformed invoice id are 404. */
    @Test
    void invoicesAre404WhenMissingOrMalformed() {
        assertThat(api.get("/invoices/inv_00000000000000000000000000").status()).isEqualTo(404);
        assertThat(api.get("/invoices/inv_nope").status()).isEqualTo(404);
        assertThat(api.patch("/invoices/inv_00000000000000000000000000", Fixtures.invoiceOf(create("flight"))).status()).isEqualTo(404);
        assertThat(api.delete("/invoices/inv_00000000000000000000000000").status()).isEqualTo(404);
    }

    /** Brief test 9: deleting an invoice removes it and its snapshot lines; the transactions still GET. */
    @Test
    void deletingAnInvoiceRemovesItAndLeavesTheTransactions() {
        String[] trip = createTrip();
        String invoiceId = createInvoice(trip);

        assertThat(api.delete("/invoices/" + invoiceId).status()).isEqualTo(204);

        assertThat(api.get("/invoices/" + invoiceId).status()).isEqualTo(404);
        for (String id : trip) {
            assertThat(api.get("/transactions/" + id).status()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_transactions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_tax_lines", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_fee_lines", Integer.class)).isZero();
        assertThat(api.delete("/invoices/" + invoiceId).status()).isEqualTo(404);
    }

    /** PATCH replaces the whole id set and rebuilds totals and lines from the new set. */
    @Test
    void patchingAnInvoiceReplacesItsTransactionSet() {
        String flightId = create("flight");
        String hotelId = create("hotel");
        String invoiceId = createInvoice(flightId);

        Resp patched = api.patch("/invoices/" + invoiceId, Fixtures.invoiceOf(flightId, hotelId));

        assertThat(patched.status()).isEqualTo(200);
        assertMoney(patched.body().get("totals").get("grand_total"), "551.70");
        assertThat(patched.body().get("transaction_count").asInt()).isEqualTo(2);

        Resp narrowed = api.patch("/invoices/" + invoiceId, java.util.Map.of("transaction_id", hotelId));
        assertMoney(narrowed.body().get("totals").get("grand_total"), "331.70");
        assertThat(narrowed.body().get("transaction_ids")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_tax_lines", Integer.class)).isEqualTo(1);
    }

    /** A PATCH that would mix currencies is a 409 and the invoice keeps its old snapshot. */
    @Test
    void patchingAnInvoiceWithMixedCurrenciesIs409AndLeavesItUnchanged() {
        String flightId = create("flight");
        ObjectNode usdBody = Fixtures.seed("trip_fee", "navan-trip-fee-usd");
        usdBody.put("currency", "USD");
        String usd = api.post("/transactions", usdBody).id();
        String invoiceId = createInvoice(flightId);
        JsonNode before = api.get("/invoices/" + invoiceId).body();

        Resp response = api.patch("/invoices/" + invoiceId, Fixtures.invoiceOf(flightId, usd));

        assertThat(response.status()).isEqualTo(409);
        JsonNode after = api.get("/invoices/" + invoiceId).body();
        assertThat(after.get("transactions")).isEqualTo(before.get("transactions"));
        assertThat(after.get("totals")).isEqualTo(before.get("totals"));
    }

    /** A PATCH naming an unknown transaction is a 404 and the invoice keeps its old snapshot. */
    @Test
    void patchingAnInvoiceWithAMissingTransactionIs404AndLeavesItUnchanged() {
        String flightId = create("flight");
        String invoiceId = createInvoice(flightId);

        Resp response = api.patch("/invoices/" + invoiceId, Fixtures.invoiceOf(flightId, "txn_00000000000000000000000001"));

        assertThat(response.status()).isEqualTo(404);
        assertMoney(api.get("/invoices/" + invoiceId).body().get("totals").get("grand_total"), "220.00");
    }

    private static JsonNode find(JsonNode array, String name) {
        for (JsonNode node : array) {
            if (node.get("name").asText().equals(name)) {
                return node;
            }
        }
        throw new AssertionError("no rollup entry named " + name + " in " + array);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
