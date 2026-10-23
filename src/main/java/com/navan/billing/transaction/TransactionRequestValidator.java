package com.navan.billing.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.common.error.ApiException.BadRequestException;
import com.navan.billing.common.error.ApiException.ValidationFailedException;
import com.navan.billing.common.JsonReader;
import com.navan.billing.common.MoneyRules;
import com.navan.billing.common.error.Violations;
import com.navan.billing.transaction.TransactionRecords.FeeLine;
import com.navan.billing.transaction.TransactionRecords.TaxLine;
import com.navan.billing.transaction.TransactionRecords.TransactionRow;
import com.navan.billing.transaction.metadata.Metadata;
import com.navan.billing.transaction.metadata.MetadataValidator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.springframework.stereotype.Component;

/** Structural validation of create and PATCH bodies. Unknown top-level fields are ignored (tolerant reader). */
@Component
public class TransactionRequestValidator {

    static final int MAX_METADATA_CHARS = 65_536;
    private static final int MAX_LINES = 100;
    private static final List<String> IMMUTABLE = List.of("id", "external_id", "type", "currency");

    private final MetadataValidator metadataValidator;
    private final ObjectMapper mapper;

    public TransactionRequestValidator(MetadataValidator metadataValidator, ObjectMapper mapper) {
        this.metadataValidator = metadataValidator;
        this.mapper = mapper;
    }

    /** The identity-first envelope check: just enough to look the transaction up. */
    public String externalId(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ValidationFailedException.single("", "request body must be a JSON object");
        }
        Violations violations = new Violations();
        String externalId = new JsonReader(violations).text(body, "external_id", "", true, 128);
        if (externalId != null && externalId.chars().anyMatch(Character::isISOControl)) {
            violations.add("/external_id", "must not contain control characters");
        }
        violations.throwIfAny();
        return externalId;
    }

    public NewTransaction validateNew(JsonNode body, String externalId) {
        Violations violations = new Violations();
        JsonReader reader = new JsonReader(violations);
        TransactionType type = type(reader, body, violations);
        Currency currency = currency(reader, body, violations);
        OffsetDateTime occurredAt = reader.instant(body, "occurred_at", "", true);
        BigDecimal total = reader.money(body, "total", "", true, currency);
        List<TaxLine> taxLines = taxLines(reader, body, currency, true);
        List<FeeLine> feeLines = feeLines(reader, body, currency, true);
        Metadata metadata = null;
        if (type != null) {
            metadata = metadataValidator.validate(type, body.get("metadata"), currency, violations, "/metadata");
        } else if (!body.path("metadata").isObject()) {
            violations.add("/metadata", body.hasNonNull("metadata") ? "must be an object" : "is required");
        }
        checkMetadataSize(body.get("metadata"), violations);
        violations.throwIfAny();
        return new NewTransaction(type, externalId, currency.getCurrencyCode(), occurredAt, total, taxLines, feeLines,
                (ObjectNode) body.get("metadata"), metadata.feeKind());
    }

    /** Validates a PATCH against the stored row (type and currency are fixed at create time). */
    public TransactionPatch validatePatch(JsonNode body, TransactionRow stored) {
        if (body == null || !body.isObject()) {
            throw ValidationFailedException.single("", "request body must be a JSON object");
        }
        rejectImmutableChanges(body, stored);
        Violations violations = new Violations();
        JsonReader reader = new JsonReader(violations);
        Currency currency = MoneyRules.parseCurrency(stored.currency());
        TransactionType type = TransactionType.fromWire(stored.type()).orElseThrow();
        boolean any = false;
        OffsetDateTime occurredAt = null;
        BigDecimal total = null;
        List<TaxLine> taxLines = null;
        List<FeeLine> feeLines = null;
        ObjectNode metadataNode = null;
        String feeKind = null;

        for (String field : List.of("occurred_at", "total", "tax_lines", "fee_lines", "metadata")) {
            if (body.has(field)) {
                any = true;
                if (body.get(field).isNull()) {
                    violations.add("/" + field, "must not be null; omit the field to leave it unchanged");
                }
            }
        }
        if (body.hasNonNull("occurred_at")) {
            occurredAt = reader.instant(body, "occurred_at", "", true);
        }
        if (body.hasNonNull("total")) {
            total = reader.money(body, "total", "", true, currency);
        }
        if (body.hasNonNull("tax_lines")) {
            taxLines = taxLines(reader, body, currency, true);
        }
        if (body.hasNonNull("fee_lines")) {
            feeLines = feeLines(reader, body, currency, true);
        }
        if (body.hasNonNull("metadata")) {
            Metadata metadata = metadataValidator.validate(type, body.get("metadata"), currency, violations, "/metadata");
            checkMetadataSize(body.get("metadata"), violations);
            if (metadata != null) {
                metadataNode = (ObjectNode) body.get("metadata");
                feeKind = metadata.feeKind();
            }
        }
        if (!any) {
            throw new BadRequestException("EMPTY_PATCH",
                    "Nothing to update; send at least one of total, tax_lines, fee_lines, metadata, occurred_at");
        }
        violations.throwIfAny();
        return new TransactionPatch(occurredAt, total, taxLines, feeLines, metadataNode, feeKind);
    }

    private void rejectImmutableChanges(JsonNode body, TransactionRow stored) {
        List<String> changed = new ArrayList<>();
        String[] storedValues = {stored.id(), stored.externalId(), stored.type(), stored.currency()};
        for (int i = 0; i < IMMUTABLE.size(); i++) {
            JsonNode sent = body.get(IMMUTABLE.get(i));
            if (sent != null && !(sent.isTextual() && sent.asText().equals(storedValues[i]))) {
                changed.add(IMMUTABLE.get(i));
            }
        }
        if (!changed.isEmpty()) {
            throw new BadRequestException("FIELD_IMMUTABLE",
                    "These fields cannot be changed after creation (delete and recreate instead): " + String.join(", ", changed))
                    .with("fields", changed);
        }
    }

    private TransactionType type(JsonReader reader, JsonNode body, Violations violations) {
        String value = reader.text(body, "type", "", true, 32);
        if (value == null) {
            return null;
        }
        TransactionType type = TransactionType.fromWire(value).orElse(null);
        if (type == null) {
            violations.add("/type", "must be one of flight, hotel, rail, navan_fee");
        }
        return type;
    }

    private Currency currency(JsonReader reader, JsonNode body, Violations violations) {
        String value = reader.text(body, "currency", "", true, 8);
        if (value == null) {
            return null;
        }
        Currency currency = MoneyRules.parseCurrency(value);
        if (currency == null) {
            violations.add("/currency", "must be an uppercase ISO 4217 currency code, e.g. EUR");
        }
        return currency;
    }

    private List<TaxLine> taxLines(JsonReader reader, JsonNode body, Currency currency, boolean required) {
        ArrayNode array = reader.array(body, "tax_lines", "", required, false, MAX_LINES);
        if (array == null) {
            return null;
        }
        List<TaxLine> lines = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            ObjectNode line = reader.objectAt(array, i, "/tax_lines");
            if (line != null) {
                String at = "/tax_lines/" + i;
                lines.add(new TaxLine(reader.text(line, "name", at, true, 128), reader.rate(line, "rate", at),
                        reader.money(line, "amount", at, true, currency)));
            }
        }
        return lines;
    }

    private List<FeeLine> feeLines(JsonReader reader, JsonNode body, Currency currency, boolean required) {
        ArrayNode array = reader.array(body, "fee_lines", "", required, false, MAX_LINES);
        if (array == null) {
            return null;
        }
        List<FeeLine> lines = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            ObjectNode line = reader.objectAt(array, i, "/fee_lines");
            if (line != null) {
                String at = "/fee_lines/" + i;
                lines.add(new FeeLine(reader.text(line, "name", at, true, 128), reader.money(line, "amount", at, true, currency)));
            }
        }
        return lines;
    }

    private void checkMetadataSize(JsonNode metadata, Violations violations) {
        if (metadata != null && metadata.isObject() && serialize(metadata).length() > MAX_METADATA_CHARS) {
            violations.add("/metadata", "must be at most " + MAX_METADATA_CHARS + " characters when serialised");
        }
    }

    String serialize(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
