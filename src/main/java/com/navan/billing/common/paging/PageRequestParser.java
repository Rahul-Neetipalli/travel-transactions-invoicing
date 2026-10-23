package com.navan.billing.common.paging;

import com.navan.billing.common.error.ApiException;
import com.navan.billing.common.error.ApiException.BadRequestException;
import com.navan.billing.common.id.IdPrefix;
import com.navan.billing.common.paging.Paging.PageRequest;
import org.springframework.stereotype.Component;

/** Validates list query parameters: limit 1-100 (default 50), and either a cursor or a capped offset. */
@Component
public class PageRequestParser {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_OFFSET = 10_000;

    public PageRequest parse(String limit, String cursor, String offset, IdPrefix idType) {
        int parsedLimit = DEFAULT_LIMIT;
        if (limit != null) {
            parsedLimit = parseInt(limit, "limit");
            if (parsedLimit < 1 || parsedLimit > MAX_LIMIT) {
                throw invalid("limit must be between 1 and " + MAX_LIMIT);
            }
        }
        if (cursor != null && offset != null) {
            throw invalid("cursor and offset cannot be combined; use one or the other");
        }
        int parsedOffset = 0;
        if (offset != null) {
            parsedOffset = parseInt(offset, "offset");
            if (parsedOffset < 0 || parsedOffset > MAX_OFFSET) {
                throw invalid("offset must be between 0 and " + MAX_OFFSET + "; use the cursor for deeper pages");
            }
        }
        String afterId = null;
        if (cursor != null) {
            afterId = Cursor.decode(cursor, idType);
            if (afterId == null) {
                throw invalid("cursor is not valid");
            }
        }
        return new PageRequest(parsedLimit, afterId, parsedOffset);
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid(name + " must be an integer");
        }
    }

    private static ApiException invalid(String detail) {
        return new BadRequestException("INVALID_PAGINATION", detail).with("max_limit", MAX_LIMIT);
    }
}
