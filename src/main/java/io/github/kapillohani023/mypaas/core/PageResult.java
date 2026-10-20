package io.github.kapillohani023.mypaas.core;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total) {
    public static <T> PageResult<T> of(List<T> items, PageRequest request, long total) {
        return new PageResult<>(List.copyOf(items), request.page(), request.size(), total);
    }
}
