package com.navan.billing.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;

/** The assignment's fixtures, read from fixtures/task-b/ (Maven runs tests from the project root). */
public final class Fixtures {

    private static final JsonNode SEEDS = read("fixtures/task-b/seed-payloads.json");
    private static final JsonNode EXPECTED = read("fixtures/task-b/expected-invoice.json");

    private Fixtures() {
    }

    public static ObjectNode seed(String name) {
        return ((ObjectNode) SEEDS.get(name)).deepCopy();
    }

    /** A copy of a seed with a different external_id, so many distinct transactions can be created. */
    public static ObjectNode seed(String name, String externalId) {
        ObjectNode copy = seed(name);
        copy.put("external_id", externalId);
        return copy;
    }

    public static ObjectNode expectedInvoice() {
        return ((ObjectNode) EXPECTED).deepCopy();
    }

    public static ObjectNode invoiceOf(String... transactionIds) {
        ObjectNode body = Api.mapper().createObjectNode();
        ArrayNode ids = body.putArray("transaction_ids");
        for (String id : transactionIds) {
            ids.add(id);
        }
        return body;
    }

    private static JsonNode read(String path) {
        try {
            return Api.mapper().readTree(new File(path));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read fixture " + path, e);
        }
    }
}
