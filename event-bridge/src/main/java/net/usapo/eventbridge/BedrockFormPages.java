package net.usapo.eventbridge;

import java.util.List;

final class BedrockFormPages {
    private BedrockFormPages() {}

    static <T> Page<T> select(List<T> allItems, int requestedPage, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        int pages = Math.max(1, (allItems.size() + pageSize - 1) / pageSize);
        int page = Math.min(Math.max(1, requestedPage), pages);
        List<T> items = allItems.stream()
                .skip((long) (page - 1) * pageSize)
                .limit(pageSize)
                .toList();
        return new Page<>(items, page, pages);
    }

    record Page<T>(List<T> items, int number, int total) {
        Page {
            items = List.copyOf(items);
        }
    }
}
