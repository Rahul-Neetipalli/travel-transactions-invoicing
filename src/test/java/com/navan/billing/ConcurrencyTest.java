package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.support.Api.Resp;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Brief test 6 plus the races the design must survive. Assertions are about invariants that must hold
 * whichever side wins, never about who wins.
 */
class ConcurrencyTest extends ApiTestBase {

    private static final int THREADS = 16;

    /** Brief test 6: 16 threads POST the same external_id at once, 5 rounds. One 201, fifteen 409s naming the winner, one row. */
    @Test
    void overlappingPostsWithTheSameExternalIdLeaveExactlyOneRow() throws Exception {
        for (int round = 0; round < 5; round++) {
            String externalId = "race-flight-" + round;
            List<Resp> responses = runTogether(THREADS, i -> () -> api.post("/transactions", Fixtures.seed("flight", externalId)));

            List<Resp> created = responses.stream().filter(r -> r.status() == 201).toList();
            List<Resp> conflicts = responses.stream().filter(r -> r.status() == 409).toList();
            assertThat(created).as("round %d: exactly one winner, got %s", round, statuses(responses)).hasSize(1);
            assertThat(conflicts).as("round %d: everyone else is told it already exists", round).hasSize(THREADS - 1);
            conflicts.forEach(conflict -> {
                assertThat(conflict.body().get("id").asText()).isEqualTo(created.get(0).id());
                assertThat(conflict.body().get("existing_id").asText()).isEqualTo(created.get(0).id());
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE external_id = ?", Integer.class, externalId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_tax_lines t JOIN transactions x ON x.id = t.transaction_id WHERE x.external_id = ?",
                    Integer.class, externalId)).as("no orphaned or doubled tax lines").isEqualTo(1);
        }
    }

    /** Control case: concurrent creates with different external_ids do not block or reject each other. */
    @Test
    void differentExternalIdsCreatedTogetherAllSucceed() throws Exception {
        List<Resp> responses = runTogether(THREADS, i -> () -> api.post("/transactions", Fixtures.seed("hotel", "parallel-" + i)));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(THREADS);
    }

    /**
     * A PATCH racing the creation of an invoice for the same transaction. Either the PATCH wins (200) and
     * the invoice snapshots the new value, or the invoice wins and the PATCH is refused (409) and the
     * snapshot holds the original. What must never happen: a 200 PATCH next to an invoice with the old value.
     */
    @Test
    void patchRacingInvoiceCreationNeverLeavesAStaleSnapshot() throws Exception {
        int patchWon = 0;
        int invoiceWon = 0;
        for (int round = 0; round < 30; round++) {
            String flightId = api.post("/transactions", Fixtures.seed("flight", "patch-race-" + round)).id();

            List<Resp> responses = runTogether(2, i -> i == 0
                    ? () -> api.patch("/transactions/" + flightId, Map.of("total", 777))
                    : () -> api.post("/invoices", Fixtures.invoiceOf(flightId)));
            Resp patch = responses.get(0);
            Resp invoice = responses.get(1);

            assertThat(invoice.status()).as("round %d invoice: %s", round, invoice.raw()).isEqualTo(201);
            assertThat(patch.status()).as("round %d patch: %s", round, patch.raw()).isIn(200, 409);
            JsonNode snapshot = api.get("/invoices/" + invoice.id()).body().get("transactions").get(0);
            JsonNode live = api.get("/transactions/" + flightId).body();
            if (patch.status() == 200) {
                patchWon++;
                assertMoney(snapshot.get("total"), "777");
                assertMoney(live.get("total"), "777");
            } else {
                invoiceWon++;
                assertMoney(snapshot.get("total"), "220");
                assertMoney(live.get("total"), "220");
            }
        }
        System.out.printf("patch-vs-invoice race: patch won %d, invoice won %d%n", patchWon, invoiceWon);
    }

    /** DELETE racing invoice creation: either the delete wins (invoice gets 404) or the invoice wins (delete gets 409). Never both. */
    @Test
    void deleteRacingInvoiceCreationNeverLeavesAnInvoiceOfAMissingTransaction() throws Exception {
        for (int round = 0; round < 30; round++) {
            String flightId = api.post("/transactions", Fixtures.seed("flight", "delete-race-" + round)).id();

            List<Resp> responses = runTogether(2, i -> i == 0
                    ? () -> api.delete("/transactions/" + flightId)
                    : () -> api.post("/invoices", Fixtures.invoiceOf(flightId)));
            Resp delete = responses.get(0);
            Resp invoice = responses.get(1);

            if (delete.status() == 204) {
                assertThat(invoice.status()).as("round %d: %s", round, invoice.raw()).isEqualTo(404);
                assertThat(api.get("/transactions/" + flightId).status()).isEqualTo(404);
            } else {
                assertThat(delete.status()).as("round %d: %s", round, delete.raw()).isEqualTo(409);
                assertThat(invoice.status()).isEqualTo(201);
                assertThat(api.get("/transactions/" + flightId).status()).isEqualTo(200);
            }
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice_transactions i WHERE NOT EXISTS (SELECT 1 FROM transactions t WHERE t.id = i.transaction_id)",
                Integer.class)).isZero();
    }

    /** Two invoices over the same transactions listed in opposite order: the fixed lock order prevents a deadlock. */
    @Test
    void invoicePatchesOverTheSameTransactionsInOppositeOrderBothComplete() throws Exception {
        String a = api.post("/transactions", Fixtures.seed("flight", "order-a")).id();
        String b = api.post("/transactions", Fixtures.seed("flight", "order-b")).id();
        String first = createInvoice(a, b);
        String second = createInvoice(b, a);

        for (int round = 0; round < 20; round++) {
            List<Resp> responses = runTogether(2, i -> i == 0
                    ? () -> api.patch("/invoices/" + first, Fixtures.invoiceOf(a, b))
                    : () -> api.patch("/invoices/" + second, Fixtures.invoiceOf(b, a)));
            assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.raw()).isEqualTo(200));
        }
        assertMoney(api.get("/invoices/" + first).body().get("totals").get("grand_total"), "440");
        assertMoney(api.get("/invoices/" + second).body().get("totals").get("grand_total"), "440");
    }

    /** Eight PATCHes of one transaction at once: last write wins, but the total, tax lines and fee lines all come from the same request. */
    @Test
    void overlappingPatchesOfTheSameTransactionNeverTearTheLists() throws Exception {
        String flightId = create("flight");

        for (int round = 0; round < 10; round++) {
            List<Resp> responses = runTogether(8, i -> {
                int n = i + 1;
                Map<String, Object> patch = Map.of(
                        "total", 100 + n,
                        "tax_lines", List.of(Map.of("name", "T" + n, "amount", 1), Map.of("name", "U" + n, "amount", 2)),
                        "fee_lines", List.of(Map.of("name", "F" + n, "amount", 3), Map.of("name", "G" + n, "amount", 4)));
                return () -> api.patch("/transactions/" + flightId, patch);
            });
            assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.raw()).isEqualTo(200));

            JsonNode stored = api.get("/transactions/" + flightId).body();
            String suffix = stored.get("tax_lines").get(0).get("name").asText().substring(1);
            assertThat(stored.get("tax_lines")).hasSize(2);
            assertThat(stored.get("fee_lines")).hasSize(2);
            assertThat(stored.get("tax_lines").get(1).get("name").asText()).isEqualTo("U" + suffix);
            assertThat(stored.get("fee_lines").get(0).get("name").asText()).isEqualTo("F" + suffix);
            assertThat(stored.get("fee_lines").get(1).get("name").asText()).isEqualTo("G" + suffix);
            assertMoney(stored.get("total"), String.valueOf(100 + Integer.parseInt(suffix)));
        }
    }

    /** Eight overlapping DELETEs of one transaction: exactly one succeeds, the rest find nothing. */
    @Test
    void overlappingDeletesOfOneTransactionSucceedExactlyOnce() throws Exception {
        String flightId = create("flight");

        List<Resp> responses = runTogether(8, i -> () -> api.delete("/transactions/" + flightId));

        assertThat(statuses(responses).stream().filter(s -> s == 204)).hasSize(1);
        assertThat(statuses(responses).stream().filter(s -> s == 404)).hasSize(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_tax_lines", Integer.class)).isZero();
    }

    /** PATCH and DELETE of the same invoice at once: either order is fine, but nothing is left half-built. */
    @Test
    void invoicePatchRacingInvoiceDeleteLeavesNoOrphanSnapshot() throws Exception {
        String flightId = create("flight");
        String hotelId = create("hotel");
        for (int round = 0; round < 20; round++) {
            String invoiceId = createInvoice(flightId);

            List<Resp> responses = runTogether(2, i -> i == 0
                    ? () -> api.patch("/invoices/" + invoiceId, Fixtures.invoiceOf(flightId, hotelId))
                    : () -> api.delete("/invoices/" + invoiceId));

            assertThat(responses.get(0).status()).as("patch, round %d", round).isIn(200, 404);
            assertThat(responses.get(1).status()).as("delete, round %d", round).isEqualTo(204);
            assertThat(api.get("/invoices/" + invoiceId).status()).isEqualTo(404);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_transactions WHERE invoice_id = ?", Integer.class, invoiceId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_tax_lines WHERE invoice_id = ?", Integer.class, invoiceId)).isZero();
        }
    }

    /** Many PATCHes of one invoice with different id sets: the survivor is one complete, self-consistent snapshot. */
    @Test
    void overlappingInvoicePatchesNeverTearTheSnapshot() throws Exception {
        String[] trip = createTrip();
        String invoiceId = createInvoice(trip[0]);

        for (int round = 0; round < 10; round++) {
            List<Resp> responses = runTogether(5, i -> {
                String[] subset = java.util.Arrays.copyOf(trip, i + 1);
                return () -> api.patch("/invoices/" + invoiceId, Fixtures.invoiceOf(subset));
            });
            assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.raw()).isEqualTo(200));

            JsonNode invoice = api.get("/invoices/" + invoiceId).body();
            int count = invoice.get("transaction_count").asInt();
            assertThat(invoice.get("transactions")).hasSize(count);
            java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
            for (JsonNode line : invoice.get("transactions")) {
                sum = sum.add(line.get("total").decimalValue());
            }
            assertThat(invoice.get("totals").get("grand_total").decimalValue()).isEqualByComparingTo(sum);
            int snapshotTaxLines = jdbc.queryForObject("SELECT COUNT(*) FROM invoice_tax_lines WHERE invoice_id = ?", Integer.class, invoiceId);
            int expectedTaxLines = count >= 2 ? 2 : 1;   // flight has 1 tax line, hotel 1, the rest none
            assertThat(snapshotTaxLines).isEqualTo(expectedTaxLines);
        }
    }

    // ---- helpers ----

    /** Starts one task per index behind a barrier so the requests really overlap. */
    private static <T> List<T> runTogether(int count, java.util.function.IntFunction<Callable<T>> taskFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CyclicBarrier barrier = new CyclicBarrier(count);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<T> task = taskFor.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> statuses(List<Resp> responses) {
        return responses.stream().map(Resp::status).toList();
    }
}
