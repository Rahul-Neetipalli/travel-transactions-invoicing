package com.navan.billing.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navan.billing.common.error.Violations;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Currency;

/** Typed, pointer-aware field access over a parsed JSON tree. Problems are recorded, never thrown. */
public final class JsonReader {

    private final Violations violations;

    public JsonReader(Violations violations) {
        this.violations = violations;
    }

    public String text(JsonNode obj, String field, String pointer, boolean required, int maxLength) {
        JsonNode node = present(obj, field, pointer, required);
        if (node == null) {
            return null;
        }
        String at = pointer + "/" + field;
        if (!node.isTextual()) {
            violations.add(at, "must be a string");
            return null;
        }
        String value = node.asText();
        if (value.isBlank()) {
            violations.add(at, "must not be blank");
            return null;
        }
        if (value.length() > maxLength) {
            violations.add(at, "must be at most " + maxLength + " characters");
            return null;
        }
        return value;
    }

    public BigDecimal money(JsonNode obj, String field, String pointer, boolean required, Currency currency) {
        BigDecimal value = number(obj, field, pointer, required);
        if (value == null) {
            return null;
        }
        String problem = MoneyRules.amountProblem(value, currency);
        if (problem != null) {
            violations.add(pointer + "/" + field, problem);
            return null;
        }
        return value;
    }

    public BigDecimal rate(JsonNode obj, String field, String pointer) {
        BigDecimal value = number(obj, field, pointer, false);
        if (value == null) {
            return null;
        }
        String problem = MoneyRules.rateProblem(value);
        if (problem != null) {
            violations.add(pointer + "/" + field, problem);
            return null;
        }
        return value;
    }

    public Integer wholeNumber(JsonNode obj, String field, String pointer, boolean required) {
        JsonNode node = present(obj, field, pointer, required);
        if (node == null) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            violations.add(pointer + "/" + field, "must be a non-negative integer");
            return null;
        }
        return node.intValue();
    }

    /** ISO-8601 instant that carries an offset (or Z); an offset-less timestamp is ambiguous. */
    public OffsetDateTime instant(JsonNode obj, String field, String pointer, boolean required) {
        String value = text(obj, field, pointer, required, 64);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            violations.add(pointer + "/" + field, "must be an ISO-8601 timestamp with an offset, e.g. 2026-03-11T06:40:00Z");
            return null;
        }
    }

    /** Valid ISO-8601 date-time, kept verbatim by the caller (airport-local times are legitimate). */
    public String timestamp(JsonNode obj, String field, String pointer, boolean required) {
        String value = text(obj, field, pointer, required, 64);
        if (value == null) {
            return null;
        }
        try {
            DateTimeFormatter.ISO_DATE_TIME.parse(value);
            return value;
        } catch (DateTimeParseException e) {
            violations.add(pointer + "/" + field, "must be an ISO-8601 date-time");
            return null;
        }
    }

    public LocalDate date(JsonNode obj, String field, String pointer, boolean required) {
        String value = text(obj, field, pointer, required, 32);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            violations.add(pointer + "/" + field, "must be an ISO-8601 date, e.g. 2026-03-11");
            return null;
        }
    }

    public ObjectNode object(JsonNode obj, String field, String pointer, boolean required) {
        JsonNode node = present(obj, field, pointer, required);
        if (node == null) {
            return null;
        }
        if (!node.isObject()) {
            violations.add(pointer + "/" + field, "must be an object");
            return null;
        }
        return (ObjectNode) node;
    }

    public ArrayNode array(JsonNode obj, String field, String pointer, boolean required, boolean nonEmpty, int maxSize) {
        JsonNode node = present(obj, field, pointer, required);
        if (node == null) {
            return null;
        }
        String at = pointer + "/" + field;
        if (!node.isArray()) {
            violations.add(at, "must be an array");
            return null;
        }
        if (nonEmpty && node.isEmpty()) {
            violations.add(at, "must contain at least one element");
            return null;
        }
        if (node.size() > maxSize) {
            violations.add(at, "must contain at most " + maxSize + " elements");
            return null;
        }
        return (ArrayNode) node;
    }

    /** Element {@code index} of an array, which must itself be an object. */
    public ObjectNode objectAt(ArrayNode array, int index, String arrayPointer) {
        JsonNode node = array.get(index);
        if (!node.isObject()) {
            violations.add(arrayPointer + "/" + index, "must be an object");
            return null;
        }
        return (ObjectNode) node;
    }

    private BigDecimal number(JsonNode obj, String field, String pointer, boolean required) {
        JsonNode node = present(obj, field, pointer, required);
        if (node == null) {
            return null;
        }
        if (!node.isNumber()) {
            violations.add(pointer + "/" + field, "must be a number");
            return null;
        }
        return node.decimalValue();
    }

    private JsonNode present(JsonNode obj, String field, String pointer, boolean required) {
        JsonNode node = obj.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                violations.add(pointer + "/" + field, "is required");
            }
            return null;
        }
        return node;
    }
}
