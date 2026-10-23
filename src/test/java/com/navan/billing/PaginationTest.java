package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.support.Api.Resp;
import com.navan.billing.support.ApiTestBase;
import com.navan.billing.support.Fixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Brief test 10, plus cursor and offset behaviour. */
class PaginationTest extends ApiTestBase {

    /** Brief test 10: with 60 rows the default page holds 50, limit=1000 is a 400, and limit=100 is allowed. */
    @Test
    void defaultPageSizeIs50AndALimitOf1000IsRefused() {
        createMany(60);

        Resp page = api.get("/transactions");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().get("items")).hasSize(50);
        assertThat(page.body().get("next_cursor").isNull()).isFalse();

        Resp tooBig = api.get("/transactions?limit=1000");
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(tooBig.body().get("code").asText()).isEqualTo("INVALID_PAGINATION");
        assertThat(tooBig.body().get("max_limit").asInt()).isEqualTo(100);

        assertThat(api.get("/transactions?limit=100").body().get("items")).hasSize(60);
    }

    /** Zero, negative, above 100 and non-integer limits are 400 on both lists. */
    @Test
    void invalidLimitsAreRefused() {
        for (String bad : new String[]{"0", "-1", "101", "abc", "1.5"}) {
            assertThat(api.get("/transactions?limit=" + bad).status()).as("limit=" + bad).isEqualTo(400);
            assertThat(api.get("/invoices?limit=" + bad).status()).as("invoices limit=" + bad).isEqualTo(400);
        }
    }

    /** Following next_cursor visits every row exactly once, newest first, and stops. */
    @Test
    void walkingTheCursorVisitsEveryRowOnceNewestFirst() {
        List<String> created = createMany(7);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Resp page = api.get("/transactions?limit=3" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).isEqualTo(200);
            page.body().get("items").forEach(item -> seen.add(item.get("id").asText()));
            JsonNode next = page.body().get("next_cursor");
            cursor = next.isNull() ? null : next.asText();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        List<String> newestFirst = new ArrayList<>(created);
        java.util.Collections.reverse(newestFirst);
        assertThat(seen).containsExactlyElementsOf(newestFirst);
    }

    /** Rows created mid-walk do not shift the pages already being read (the weakness of offset paging). */
    @Test
    void aCursorStaysStableWhenRowsAreInsertedBetweenPages() {
        createMany(5);
        Resp first = api.get("/transactions?limit=2");
        String cursor = first.body().get("next_cursor").asText();

        createMany(3, "late");

        List<String> rest = new ArrayList<>();
        do {
            Resp page = api.get("/transactions?limit=2&cursor=" + cursor);
            page.body().get("items").forEach(item -> rest.add(item.get("external_id").asText()));
            cursor = page.body().get("next_cursor").isNull() ? null : page.body().get("next_cursor").asText();
        } while (cursor != null);

        assertThat(rest).hasSize(3).doesNotContainAnyElementsOf(List.of("late-0", "late-1", "late-2"));
    }

    /** Offset paging works, is capped at 10 000, and cannot be mixed with a cursor. */
    @Test
    void offsetWorksAndIsCappedAndCannotBeCombinedWithACursor() {
        List<String> created = createMany(5);

        Resp page = api.get("/transactions?limit=2&offset=2");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().get("items")).hasSize(2);
        assertThat(page.body().get("items").get(0).get("id").asText()).isEqualTo(created.get(2));
        assertThat(page.body().get("next_cursor").isNull()).isFalse();

        assertThat(api.get("/transactions?offset=10001").status()).isEqualTo(400);
        assertThat(api.get("/transactions?offset=-1").status()).isEqualTo(400);
        String cursor = api.get("/transactions?limit=1").body().get("next_cursor").asText();
        assertThat(api.get("/transactions?cursor=" + cursor + "&offset=1").status()).isEqualTo(400);
        assertThat(api.get("/transactions?cursor=garbage").status()).isEqualTo(400);
    }

    /** List items carry header fields only, never metadata or lines. */
    @Test
    void listItemsAreHeaderOnlySummaries() {
        String id = create("flight");

        JsonNode item = api.get("/transactions").body().get("items").get(0);

        assertThat(item.get("id").asText()).isEqualTo(id);
        assertThat(item.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "type", "external_id", "currency", "occurred_at", "total", "created_at");
    }

    /** ?external_id= finds the single matching transaction (or none). */
    @Test
    void externalIdFilterFindsTheOneRow() {
        create("flight");
        create("hotel");

        JsonNode items = api.get("/transactions?external_id=hotel-LON-2026-03-11").body().get("items");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("type").asText()).isEqualTo("hotel");
        assertThat(api.get("/transactions?external_id=nope").body().get("items")).isEmpty();
    }

    /** The invoice list pages the same way: summaries, cursor walk, limit cap. */
    @Test
    void invoiceListsFollowTheSameRules() {
        String flightId = create("flight");
        for (int i = 0; i < 5; i++) {
            createInvoice(flightId);
        }

        Resp first = api.get("/invoices?limit=2");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("items")).hasSize(2);
        assertThat(first.body().get("items").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "currency", "total", "transaction_count", "created_at");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            Resp page = api.get("/invoices?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            page.body().get("items").forEach(item -> seen.add(item.get("id").asText()));
            cursor = page.body().get("next_cursor").isNull() ? null : page.body().get("next_cursor").asText();
        } while (cursor != null);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(api.get("/invoices?limit=1000").status()).isEqualTo(400);
    }

    /** Nothing stored: an empty page with no cursor, not an error. */
    @Test
    void anEmptyTableGivesAnEmptyLastPage() {
        Resp page = api.get("/transactions");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().get("items")).isEmpty();
        assertThat(page.body().get("next_cursor").isNull()).isTrue();
    }

    /** When the rows end exactly on a page boundary, that page is the last one (no empty extra page). */
    @Test
    void aFullFinalPageHasNoNextCursor() {
        createMany(4);

        Resp first = api.get("/transactions?limit=2");
        Resp second = api.get("/transactions?limit=2&cursor=" + first.body().get("next_cursor").asText());

        assertThat(second.body().get("items")).hasSize(2);
        assertThat(second.body().get("next_cursor").isNull()).isTrue();
    }

    /** A cursor from the invoice list cannot be replayed against the transaction list. */
    @Test
    void aCursorOfTheWrongResourceIsRefused() {
        String flightId = create("flight");
        createInvoice(flightId);
        createInvoice(flightId);
        String invoiceCursor = api.get("/invoices?limit=1").body().get("next_cursor").asText();

        assertThat(api.get("/transactions?cursor=" + invoiceCursor).status()).isEqualTo(400);
    }

    private List<String> createMany(int count) {
        return createMany(count, "bulk");
    }

    private List<String> createMany(int count, String prefix) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Resp created = api.post("/transactions", Fixtures.seed("flight", prefix + "-" + i));
            assertThat(created.status()).isEqualTo(201);
            ids.add(created.id());
        }
        return ids;
    }
}
