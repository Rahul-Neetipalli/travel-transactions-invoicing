package com.navan.billing.common.error;

import com.navan.billing.common.error.ApiException.ValidationFailedException;
import java.util.ArrayList;
import java.util.List;

/** Collects every validation problem (JSON pointer + message) so a 400 reports them all at once. */
public final class Violations {

    public record FieldError(String pointer, String message) {
    }

    private final List<FieldError> errors = new ArrayList<>();

    public void add(String pointer, String message) {
        errors.add(new FieldError(pointer, message));
    }

    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(List.copyOf(errors));
        }
    }
}
