package com.navan.billing.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.common.error.ApiException.ConflictException;
import com.navan.billing.common.error.ApiException.ValidationFailedException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The id set of a create / PATCH invoice request: {@code transaction_id} or {@code transaction_ids}. */
public record InvoiceRequest(List<String> transactionIds) {

    public static final int MAX_IDS = 100;

    public static InvoiceRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ValidationFailedException.single("", "request body must be a JSON object");
        }
        boolean single = body.hasNonNull("transaction_id");
        boolean several = body.hasNonNull("transaction_ids");
        if (single && several) {
            throw ValidationFailedException.single("", "send either transaction_id or transaction_ids, not both");
        }
        if (!single && !several) {
            throw ValidationFailedException.single("", "transaction_id or transaction_ids is required");
        }
        List<String> ids = new ArrayList<>();
        if (single) {
            ids.add(idText(body.get("transaction_id"), "/transaction_id"));
        } else {
            JsonNode array = body.get("transaction_ids");
            if (!array.isArray() || array.isEmpty()) {
                throw ValidationFailedException.single("/transaction_ids", "must be a non-empty array of transaction ids");
            }
            if (array.size() > MAX_IDS) {
                throw ValidationFailedException.single("/transaction_ids", "must contain at most " + MAX_IDS + " ids");
            }
            for (int i = 0; i < array.size(); i++) {
                ids.add(idText(array.get(i), "/transaction_ids/" + i));
            }
        }
        rejectDuplicates(ids);
        return new InvoiceRequest(List.copyOf(ids));
    }

    private static String idText(JsonNode node, String pointer) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw ValidationFailedException.single(pointer, "must be a non-blank transaction id string");
        }
        return node.asText();
    }

    /** A repeated id is more likely a client bug than intent; fail loudly instead of silently deduplicating. */
    private static void rejectDuplicates(List<String> ids) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                duplicates.add(id);
            }
        }
        if (!duplicates.isEmpty()) {
            throw new ConflictException("DUPLICATE_TRANSACTION_ID", "The same transaction id appears more than once in the request")
                    .with("duplicate_ids", List.copyOf(duplicates));
        }
    }
}
