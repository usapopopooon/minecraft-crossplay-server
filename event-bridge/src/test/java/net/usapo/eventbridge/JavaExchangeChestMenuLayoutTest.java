package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

final class JavaExchangeChestMenuLayoutTest {
    @Test
    void dynamicCatalogLayoutsFitAllSupportedBoundarySizesWithoutCoveringBack() {
        assertLayout(1, 27, List.of(13));
        assertLayout(7, 27, List.of(10, 11, 12, 13, 14, 15, 16));
        assertLayout(8, 27, List.of(0, 1, 2, 3, 4, 5, 6, 7));
        assertLayout(18, 27, java.util.stream.IntStream.range(0, 18).boxed().toList());
        assertLayout(19, 54, java.util.stream.IntStream.range(0, 19).boxed().toList());
        assertLayout(25, 54, java.util.stream.IntStream.range(0, 25).boxed().toList());
    }

    private static void assertLayout(int optionCount, int size, List<Integer> expected) {
        assertEquals(size, JavaExchangeChestMenu.menuSize(optionCount));
        List<Integer> slots = JavaExchangeChestMenu.contentSlots(optionCount, size);
        assertEquals(expected, slots);
        assertEquals(optionCount, slots.stream().distinct().count());
        assertFalse(slots.contains(JavaExchangeChestMenu.backSlot(size)));
    }
}
