package io.github.kapillohani023.mypaas.core;

public record PageRequest(int page, int size) {

    public static PageRequest of(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null ? 25 : Math.max(1, Math.min(size, 100));
        return new PageRequest(p, s);
    }

    public int offset() {
        return page * size;
    }
}
