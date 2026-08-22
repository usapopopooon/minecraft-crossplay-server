package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class BedrockFormPagesTest {
    @Test
    void everyItemRemainsReachableAcrossPageBoundaries() {
        var items = IntStream.rangeClosed(1, 21).boxed().toList();

        var first = BedrockFormPages.select(items, 0, 8);
        var second = BedrockFormPages.select(items, 2, 8);
        var last = BedrockFormPages.select(items, 99, 8);

        assertEquals(1, first.number());
        assertEquals(3, first.total());
        assertEquals(IntStream.rangeClosed(1, 8).boxed().toList(), first.items());
        assertEquals(IntStream.rangeClosed(9, 16).boxed().toList(), second.items());
        assertEquals(IntStream.rangeClosed(17, 21).boxed().toList(), last.items());
    }

    @Test
    void emptyCollectionStillHasOneEmptyPage() {
        var page = BedrockFormPages.select(java.util.List.of(), 1, 8);

        assertEquals(1, page.number());
        assertEquals(1, page.total());
        assertEquals(java.util.List.of(), page.items());
        assertThrows(
                IllegalArgumentException.class,
                () -> BedrockFormPages.select(java.util.List.of(), 1, 0));
    }
}
