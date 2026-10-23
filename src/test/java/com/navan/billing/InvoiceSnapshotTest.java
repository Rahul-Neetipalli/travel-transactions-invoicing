package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.support.Api.Resp;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import org.junit.jupiter.api.Test;

/**
 * Brief test 4. The API (correctly) refuses to PATCH an invoiced transaction, so the stored flight is
 * changed straight in the database, the way a bug, a migration or an operator could.
 */
class InvoiceSnapshotTest extends ApiTestBase {

    /** Brief test 4: rewrite the flight's total, metadata and lines in the DB; the invoice still shows 220.00 and the old details. */
    @Test
    void changingTheStoredFlightLeavesTheInvoiceAt220() {
        String[] trip = createTrip();
        String invoiceId = createInvoice(trip);

        jdbc.update("UPDATE transactions SET total = 999.0000, metadata = ? WHERE id = ?",
                "{\"origin\":\"CHANGED\",\"destination\":\"CHANGED\",\"fare\":1,\"coupons\":[]}", trip[0]);
        jdbc.update("UPDATE transaction_tax_lines SET amount = 500 WHERE transaction_id = ?", trip[0]);
        jdbc.update("DELETE FROM transaction_fee_lines WHERE transaction_id = ?", trip[0]);

        JsonNode live = api.get("/transactions/" + trip[0]).body();
        assertMoney(live.get("total"), "999");

        JsonNode invoice = api.get("/invoices/" + invoiceId).body();
        assertMoney(invoice.get("totals").get("flight"), "220.00");
        assertMoney(invoice.get("totals").get("grand_total"), "685.70");
        JsonNode flightSnapshot = invoice.get("transactions").get(0);
        assertMoney(flightSnapshot.get("total"), "220.00");
        assertThat(flightSnapshot.get("metadata").get("origin").asText()).isEqualTo("TXL");
        assertThat(flightSnapshot.get("metadata").get("coupons")).hasSize(1);
        assertMoney(flightSnapshot.get("tax_lines").get(0).get("amount"), "15");
        assertThat(flightSnapshot.get("fee_lines")).hasSize(1);
        assertMoney(find(invoice.get("tax_rollup"), "Airport tax").get("amount"), "15");
        assertMoney(find(invoice.get("fee_rollup"), "YQ").get("amount"), "25");
    }

    /** Even identity columns changed underneath (type, external_id) do not leak into the invoice. */
    @Test
    void theSnapshotIsNotALiveJoinEvenWhenTheTransactionRowIsReplacedWholesale() {
        String flightId = create("flight");
        String invoiceId = createInvoice(flightId);

        jdbc.update("UPDATE transactions SET external_id = 'renamed', type = 'rail', total = 1 WHERE id = ?", flightId);

        JsonNode snapshot = api.get("/invoices/" + invoiceId).body().get("transactions").get(0);
        assertThat(snapshot.get("type").asText()).isEqualTo("flight");
        assertThat(snapshot.get("external_id").asText()).isEqualTo("flight-TXL-LHR-2026-03-11");
    }

    /** Re-snapshotting is explicit: only a PATCH of the invoice picks up the newer stored values. */
    @Test
    void patchingTheInvoiceTakesAFreshSnapshotOfTheCurrentStoredValues() {
        String flightId = create("flight");
        String invoiceId = createInvoice(flightId);
        jdbc.update("UPDATE transactions SET total = 300.0000 WHERE id = ?", flightId);
        assertMoney(api.get("/invoices/" + invoiceId).body().get("totals").get("grand_total"), "220.00");

        Resp patched = api.patch("/invoices/" + invoiceId, Fixtures.invoiceOf(flightId));

        assertThat(patched.status()).isEqualTo(200);
        assertMoney(patched.body().get("totals").get("grand_total"), "300.00");
    }

    private static JsonNode find(JsonNode array, String name) {
        for (JsonNode node : array) {
            if (node.get("name").asText().equals(name)) {
                return node;
            }
        }
        throw new AssertionError("no rollup entry named " + name);
    }
}
