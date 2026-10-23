package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How an invoice adds things up: rollups group taxes by (name, rate) and fees by name across all
 * transactions, totals are keyed by type (navan fees by fee kind), and amounts keep the currency's scale.
 */
class InvoiceRollupTest extends ApiTestBase {

    /** Two flights: the same tax and fee names are summed into one rollup line each. */
    @Test
    void sumsTheSameTaxAndFeeAcrossTransactions() {
        String outbound = api.post("/transactions", Fixtures.seed("flight", "flight-out")).id();
        String inbound = api.post("/transactions", Fixtures.seed("flight", "flight-back")).id();

        JsonNode invoice = api.get("/invoices/" + createInvoice(outbound, inbound)).body();

        assertThat(invoice.get("tax_rollup")).hasSize(1);
        assertMoney(invoice.get("tax_rollup").get(0).get("amount"), "30");
        assertThat(invoice.get("fee_rollup")).hasSize(1);
        assertMoney(invoice.get("fee_rollup").get(0).get("amount"), "50");
        assertMoney(invoice.get("totals").get("flight"), "440");
        assertMoney(invoice.get("totals").get("grand_total"), "440");
    }

    /** VAT at 7% and VAT at 20% are different lines; 0.07 and 0.070 are the same rate. */
    @Test
    void groupsTaxesByNameAndRate() {
        String hotelA = api.post("/transactions", withVat("hotel-a", "0.07", "21.70")).id();
        String hotelB = api.post("/transactions", withVat("hotel-b", "0.070", "10.00")).id();
        String hotelC = api.post("/transactions", withVat("hotel-c", "0.20", "5.00")).id();

        JsonNode rollup = api.get("/invoices/" + createInvoice(hotelA, hotelB, hotelC)).body().get("tax_rollup");

        assertThat(rollup).hasSize(2);
        assertMoney(rollup.get(0).get("rate"), "0.07");
        assertMoney(rollup.get(0).get("amount"), "31.70");
        assertMoney(rollup.get(1).get("rate"), "0.2");
        assertMoney(rollup.get(1).get("amount"), "5.00");
    }

    /** The rollups are a breakdown of the gross totals, never added on top of them. */
    @Test
    void grandTotalIsTheSumOfTransactionTotalsOnly() {
        JsonNode invoice = api.get("/invoices/" + createInvoice(createTrip())).body();

        BigDecimal taxes = sum(invoice.get("tax_rollup"));
        BigDecimal fees = sum(invoice.get("fee_rollup"));
        assertMoney(invoice.get("totals").get("grand_total"), "685.70");
        assertThat(taxes.add(fees)).isEqualByComparingTo("65.70");
    }

    /** Navan fees are totalled by kind; only the kinds and types actually present appear. */
    @Test
    void feeOnlyInvoiceShowsJustThatFeeKind() {
        String tripFee = create("trip_fee");

        JsonNode invoice = api.get("/invoices/" + createInvoice(tripFee)).body();

        assertThat(fieldNames(invoice.get("totals"))).containsExactly("trip_fee", "grand_total");
        assertThat(texts(invoice.get("transaction_types_included"))).containsExactly("navan_fee");
        assertThat(texts(invoice.get("navan_fee_kinds_included"))).containsExactly("trip_fee");
        assertThat(invoice.get("tax_rollup")).isEmpty();
        assertThat(invoice.get("fee_rollup")).isEmpty();
    }

    /** Lines keep the order the client asked for, not id order. */
    @Test
    void keepsTheRequestedOrderOfTransactions() {
        String[] trip = createTrip();
        String[] reversed = {trip[4], trip[3], trip[2], trip[1], trip[0]};

        JsonNode invoice = api.get("/invoices/" + createInvoice(reversed)).body();

        assertThat(texts(invoice.get("transaction_ids"))).containsExactly(reversed);
        for (int i = 0; i < reversed.length; i++) {
            assertThat(invoice.get("transactions").get(i).get("transaction_id").asText()).isEqualTo(reversed[i]);
            assertThat(invoice.get("transactions").get(i).get("line_no").asInt()).isEqualTo(i + 1);
        }
    }

    /** A JPY invoice prints whole yen, an EUR invoice always two decimals. */
    @Test
    void printsAmountsAtTheInvoiceCurrencyScale() {
        ObjectNode yen = Fixtures.seed("trip_fee", "fee-jpy");
        yen.put("currency", "JPY");
        yen.put("total", 2500);
        String yenInvoice = createInvoice(api.post("/transactions", yen).id());
        String euroInvoice = createInvoice(create("rail"));

        assertThat(api.get("/invoices/" + yenInvoice).raw()).contains("\"grand_total\":2500}");
        assertThat(api.get("/invoices/" + euroInvoice).raw()).contains("\"grand_total\":94.00}");
    }

    /** The invoice list shows the header only: id, currency, total, count, created_at. */
    @Test
    void listsInvoiceSummaries() {
        createInvoice(createTrip());

        JsonNode item = api.get("/invoices").body().get("items").get(0);

        assertMoney(item.get("total"), "685.70");
        assertThat(item.get("transaction_count").asInt()).isEqualTo(5);
        assertThat(item.has("transactions")).isFalse();
    }

    private static ObjectNode withVat(String externalId, String rate, String amount) {
        ObjectNode body = Fixtures.seed("hotel", externalId);
        ObjectNode vat = (ObjectNode) body.get("tax_lines").get(0);
        vat.put("rate", new BigDecimal(rate));
        vat.put("amount", new BigDecimal(amount));
        return body;
    }

    private static BigDecimal sum(JsonNode rollup) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : rollup) {
            total = total.add(line.get("amount").decimalValue());
        }
        return total;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> out = new ArrayList<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
