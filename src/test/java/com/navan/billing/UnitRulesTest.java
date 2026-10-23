package com.navan.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.common.error.ApiException;
import com.navan.billing.common.paging.Cursor;
import com.navan.billing.common.id.IdPrefix;
import com.navan.billing.common.paging.PageRequestParser;
import com.navan.billing.common.paging.Paging.PageRequest;
import com.navan.billing.invoice.InvoiceRequest;
import com.navan.billing.support.Api;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit tests for the small rules that sit behind the HTTP layer (no server, no database). */
class UnitRulesTest {

    private final PageRequestParser pages = new PageRequestParser();

    /** No parameters: first page of 50. */
    @Test
    void pageDefaultsToFiftyFromTheTop() {
        PageRequest page = pages.parse(null, null, null, IdPrefix.TRANSACTION);

        assertThat(page.limit()).isEqualTo(50);
        assertThat(page.afterId()).isNull();
        assertThat(page.offset()).isZero();
    }

    /** Every out-of-contract combination is a 400, never a silent fallback. */
    @ParameterizedTest(name = "limit={0} offset={1} cursor={2}")
    @CsvSource(nullValues = "null", value = {
            "0, null, null", "101, null, null", "ten, null, null",
            "null, -1, null", "null, 10001, null", "null, x, null",
            "null, null, garbage", "null, 5, dHhuXzAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw"})
    void pageParametersOutsideTheContractAreRejected(String limit, String offset, String cursor) {
        assertThatThrownBy(() -> pages.parse(limit, cursor, offset, IdPrefix.TRANSACTION))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
    }

    /** A cursor round-trips, and a cursor for invoices is not accepted for transactions. */
    @Test
    void cursorsAreOpaqueAndTypeChecked() {
        String id = "txn_01M5KRTRAEP3CH4TJ80RXMNMTC";
        String cursor = Cursor.encode(id);

        assertThat(cursor).doesNotContain("txn_");
        assertThat(Cursor.decode(cursor, IdPrefix.TRANSACTION)).isEqualTo(id);
        assertThat(Cursor.decode(cursor, IdPrefix.INVOICE)).isNull();
        assertThat(Cursor.decode("%%%", IdPrefix.TRANSACTION)).isNull();
    }

    /** Both invoice body shapes produce the same id list. */
    @Test
    void invoiceRequestAcceptsOneIdOrAList() throws Exception {
        JsonNode single = Api.mapper().readTree("{\"transaction_id\":\"txn_a\"}");
        JsonNode list = Api.mapper().readTree("{\"transaction_ids\":[\"txn_a\",\"txn_b\"]}");

        assertThat(InvoiceRequest.parse(single).transactionIds()).containsExactly("txn_a");
        assertThat(InvoiceRequest.parse(list).transactionIds()).containsExactly("txn_a", "txn_b");
    }

    /** A repeated id is a 409; a blank id or a non-string id is a 400. */
    @Test
    void invoiceRequestRejectsRepeatedAndBlankIds() throws Exception {
        assertThatThrownBy(() -> InvoiceRequest.parse(Api.mapper().readTree("{\"transaction_ids\":[\"txn_a\",\"txn_a\"]}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
        assertThatThrownBy(() -> InvoiceRequest.parse(Api.mapper().readTree("{\"transaction_ids\":[\" \"]}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
        assertThatThrownBy(() -> InvoiceRequest.parse(Api.mapper().readTree("{\"transaction_ids\":[7]}")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(400));
    }
}
