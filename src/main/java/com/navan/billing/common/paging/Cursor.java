package com.navan.billing.common.paging;

import com.navan.billing.common.id.IdPrefix;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque keyset cursor: base64url of the last id on the previous page. */
public final class Cursor {

    private Cursor() {
    }

    public static String encode(String lastId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(lastId.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the id, or null when the cursor is not a valid cursor for this id type. */
    public static String decode(String cursor, IdPrefix prefix) {
        try {
            String id = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return prefix.isWellFormed(id) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
