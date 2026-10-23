package com.navan.billing.common.paging;

import java.util.List;
import java.util.function.Function;

/** Paging shapes shared by the list endpoints. */
public final class Paging {

    private Paging() {
    }

    /** Parsed list paging: newest first by id; {@code afterId} is the keyset cursor, {@code offset} the compatibility path. */
    public record PageRequest(int limit, String afterId, int offset) {
    }

    /** One page of a list. {@code nextCursor} is null on the last page. */
    public record PageResponse<T>(List<T> items, String nextCursor) {
    }

    /**
     * Turns the {@code limit + 1} rows a list query fetched into a page: at most {@code limit} items, plus a cursor
     * to the next page when the extra row shows that one exists.
     */
    public static <R, T> PageResponse<T> toPage(List<R> rows, int limit, Function<R, String> idOf, Function<R, T> toItem) {
        boolean hasMore = rows.size() > limit;
        List<R> pageRows = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? Cursor.encode(idOf.apply(pageRows.get(pageRows.size() - 1))) : null;
        return new PageResponse<>(pageRows.stream().map(toItem).toList(), nextCursor);
    }
}
