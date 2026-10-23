package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.support.Api.Resp;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Brief tests 7 and 8 (frozen transactions), plus PATCH/DELETE behaviour before invoicing. */
class TransactionMutationTest extends ApiTestBase {

    /** Brief test 7: PATCH of an invoiced flight is a 409 naming the invoice; the transaction and invoice totals are unchanged. */
    @Test
    void patchAfterInvoicingIs409AndTheInvoiceTotalsAreUnchanged() {
        String[] trip = createTrip();
        String invoiceId = createInvoice(trip);
        JsonNode before = api.get("/invoices/" + invoiceId).body();
        JsonNode flightBefore = api.get("/transactions/" + trip[0]).body();

        Resp response = api.patch("/transactions/" + trip[0], Map.of("total", 999));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.body().get("code").asText()).isEqualTo("TRANSACTION_INVOICED");
        assertThat(response.body().get("invoice_ids").get(0).asText()).isEqualTo(invoiceId);
        assertThat(response.body().get("has_more").asBoolean()).isFalse();
        assertThat(response.body().get("hint").asText()).isNotBlank();
        assertThat(api.get("/transactions/" + trip[0]).body()).isEqualTo(flightBefore);
        JsonNode after = api.get("/invoices/" + invoiceId).body();
        assertThat(after.get("totals")).isEqualTo(before.get("totals"));
        assertMoney(after.get("totals").get("grand_total"), "685.70");
    }

    /** Frozen means frozen: even a no-op PATCH of an invoiced transaction is a 409. */
    @Test
    void anyPatchOfAnInvoicedTransactionIs409EvenWhenNothingWouldChange() {
        String flightId = create("flight");
        createInvoice(flightId);

        assertThat(api.patch("/transactions/" + flightId, Map.of("total", 220)).status()).isEqualTo(409);
    }

    /** The 409 lists every invoice holding the transaction, so the client knows what to undo. */
    @Test
    void theConflictListsEveryInvoiceTheTransactionIsOn() {
        String flightId = create("flight");
        String first = createInvoice(flightId);
        String second = createInvoice(flightId);

        Resp response = api.delete("/transactions/" + flightId);

        assertThat(response.status()).isEqualTo(409);
        List<String> listed = new java.util.ArrayList<>();
        response.body().get("invoice_ids").forEach(n -> listed.add(n.asText()));
        assertThat(listed).containsExactlyInAnyOrder(first, second);
    }

    /** Brief test 8: DELETE of an invoiced transaction is a 409 and the row and its lines remain. */
    @Test
    void deleteAfterInvoicingIs409AndTheRowStillExists() {
        String flightId = create("flight");
        createInvoice(flightId);

        Resp response = api.delete("/transactions/" + flightId);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("code").asText()).isEqualTo("TRANSACTION_INVOICED");
        assertThat(api.get("/transactions/" + flightId).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_tax_lines", Integer.class)).isEqualTo(1);
    }

    /** Defence in depth: the RESTRICT foreign key blocks the delete even when bypassing the API. */
    @Test
    void theDatabaseItselfRefusesToDeleteAnInvoicedTransaction() {
        String flightId = create("flight");
        createInvoice(flightId);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("DELETE FROM transactions WHERE id = ?", flightId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** Deleting the invoice unfreezes the transaction: PATCH and DELETE work again. */
    @Test
    void afterTheInvoiceIsDeletedTheTransactionCanBeChangedAndDeletedAgain() {
        String flightId = create("flight");
        String invoiceId = createInvoice(flightId);
        assertThat(api.patch("/transactions/" + flightId, Map.of("total", 230)).status()).isEqualTo(409);

        api.delete("/invoices/" + invoiceId);

        Resp patched = api.patch("/transactions/" + flightId, Map.of("total", 230));
        assertThat(patched.status()).isEqualTo(200);
        assertMoney(patched.body().get("total"), "230");
        assertThat(api.delete("/transactions/" + flightId).status()).isEqualTo(204);
        assertThat(api.get("/transactions/" + flightId).status()).isEqualTo(404);
    }

    /** Every patchable field can change in one PATCH; lists are replaced whole. */
    @Test
    void patchReplacesTotalTaxLinesFeeLinesMetadataAndOccurredAt() {
        String flightId = create("flight");
        ObjectNode patch = com.navan.billing.support.Api.mapper().createObjectNode();
        patch.put("total", new java.math.BigDecimal("250.50"));
        patch.put("occurred_at", "2026-03-12T10:00:00Z");
        patch.putArray("tax_lines").addObject().put("name", "UK APD").put("amount", new java.math.BigDecimal("30.50"));
        patch.putArray("fee_lines");
        ObjectNode metadata = copy(Fixtures.seed("flight").get("metadata"));
        metadata.put("destination", "STN");
        patch.set("metadata", metadata);

        Resp response = api.patch("/transactions/" + flightId, patch);

        assertThat(response.status()).isEqualTo(200);
        JsonNode stored = api.get("/transactions/" + flightId).body();
        assertMoney(stored.get("total"), "250.50");
        assertThat(stored.get("occurred_at").asText()).isEqualTo("2026-03-12T10:00:00Z");
        assertThat(stored.get("tax_lines")).hasSize(1);
        assertThat(stored.get("tax_lines").get(0).get("name").asText()).isEqualTo("UK APD");
        assertThat(stored.get("fee_lines")).isEmpty();
        assertThat(stored.get("metadata").get("destination").asText()).isEqualTo("STN");
        assertThat(stored.get("updated_at").asText()).isNotEqualTo(stored.get("created_at").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_tax_lines", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_fee_lines", Integer.class)).isZero();
    }

    /** Fields left out of a PATCH keep their stored values. */
    @Test
    void patchLeavesOmittedFieldsUntouched() {
        String flightId = create("flight");

        api.patch("/transactions/" + flightId, Map.of("total", 221));

        JsonNode stored = api.get("/transactions/" + flightId).body();
        assertMoney(stored.get("total"), "221");
        assertThat(stored.get("tax_lines")).hasSize(1);
        assertThat(stored.get("fee_lines")).hasSize(1);
        assertThat(stored.get("metadata").get("coupons")).hasSize(1);
    }

    /** external_id, type and currency are immutable (400), but resending them unchanged is accepted. */
    @Test
    void identityFieldsCannotChangeButResendingTheSameValuesIsFine() {
        String flightId = create("flight");

        Resp changed = api.patch("/transactions/" + flightId, Map.of("external_id", "other", "type", "hotel", "currency", "USD"));
        assertThat(changed.status()).isEqualTo(400);
        assertThat(changed.body().get("code").asText()).isEqualTo("FIELD_IMMUTABLE");
        assertThat(changed.body().get("fields").size()).isEqualTo(3);

        ObjectNode resent = Fixtures.seed("flight");
        resent.put("total", 221);
        Resp ok = api.patch("/transactions/" + flightId, resent);
        assertThat(ok.status()).isEqualTo(200);
        assertMoney(ok.body().get("total"), "221");
        assertThat(api.get("/transactions?external_id=other").body().get("items")).isEmpty();
    }

    /** Empty, nothing-patchable, null, wrong-type, invalid-metadata and over-precise PATCHes are 400 and change nothing. */
    @Test
    void badPatchesAre400AndChangeNothing() {
        String flightId = create("flight");
        JsonNode before = api.get("/transactions/" + flightId).body();

        assertThat(api.patch("/transactions/" + flightId, Map.of()).status()).isEqualTo(400);
        assertThat(api.patch("/transactions/" + flightId, Map.of("comment", "nothing patchable")).status()).isEqualTo(400);
        assertThat(api.patch("/transactions/" + flightId, "{\"total\":null}").status()).isEqualTo(400);
        assertThat(api.patch("/transactions/" + flightId, Map.of("total", "lots")).status()).isEqualTo(400);
        assertThat(api.patch("/transactions/" + flightId, Map.of("metadata", Map.of("origin", "TXL"))).status()).isEqualTo(400);
        assertThat(api.patch("/transactions/" + flightId, "{\"total\":220.123}").status()).isEqualTo(400);
        assertThat(api.get("/transactions/" + flightId).body()).isEqualTo(before);
    }

    /** Changing a navan fee's metadata also updates the promoted fee_kind column; an unknown kind is a 400. */
    @Test
    void patchingNavanFeeMetadataKeepsTheFeeKindInSync() {
        String feeId = create("trip_fee");
        ObjectNode metadata = copy(Fixtures.seed("trip_fee").get("metadata"));
        metadata.put("fee_kind", "agent_call_fee");

        Resp response = api.patch("/transactions/" + feeId, Map.of("metadata", metadata));

        assertThat(response.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT fee_kind FROM transactions WHERE id = ?", String.class, feeId)).isEqualTo("agent_call_fee");
        metadata.put("fee_kind", "bogus");
        assertThat(api.patch("/transactions/" + feeId, Map.of("metadata", metadata)).status()).isEqualTo(400);
    }

    /** PATCH and DELETE of an unknown or malformed transaction id are 404. */
    @Test
    void missingTransactionsAre404ForPatchAndDelete() {
        assertThat(api.patch("/transactions/txn_00000000000000000000000000", Map.of("total", 1)).status()).isEqualTo(404);
        assertThat(api.delete("/transactions/txn_00000000000000000000000000").status()).isEqualTo(404);
        assertThat(api.delete("/transactions/garbage").status()).isEqualTo(404);
    }

    /** DELETE removes the row and its lines together, and frees the external_id for reuse. */
    @Test
    void deleteRemovesTheRowAndItsTaxAndFeeLinesAtomically() {
        String flightId = create("flight");

        assertThat(api.delete("/transactions/" + flightId).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_tax_lines", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_fee_lines", Integer.class)).isZero();
        // and the external_id is free again
        assertThat(api.post("/transactions", Fixtures.seed("flight")).status()).isEqualTo(201);
    }
}
