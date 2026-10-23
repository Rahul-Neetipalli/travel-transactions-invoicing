package com.navan.billing.transaction;

import com.navan.billing.common.error.ApiException.ConflictException;


/** 409 for a known identity. Carries the existing transaction as both {@code id} and {@code existing_id}. */
public class DuplicateExternalIdException extends ConflictException {

    public DuplicateExternalIdException(String externalId, String existingId) {
        super("DUPLICATE_EXTERNAL_ID", "A transaction with external_id '" + externalId + "' already exists; nothing was created");
        with("id", existingId);
        with("existing_id", existingId);
        header("Location", "/transactions/" + existingId);
    }
}
