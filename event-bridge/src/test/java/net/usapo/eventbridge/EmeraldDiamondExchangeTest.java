package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EmeraldDiamondExchangeTest {
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void exchangesAllowedEmeraldAmountsAndPreservesOtherItems() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.EMERALD, 32),
                slot(EmeraldDiamondExchange.ItemKind.OTHER, 12),
                EmeraldDiamondExchange.InventorySlot.empty());

        EmeraldDiamondExchange.Result result = exchange.exchange(state, REQUEST_ID, 32);

        assertEquals(EmeraldDiamondExchange.Status.COMPLETED, result.status());
        assertEquals(1, result.diamondCount());
        assertFalse(result.duplicate());
        assertEquals(0, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
        assertEquals(1, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
        assertEquals(12, state.count(EmeraldDiamondExchange.ItemKind.OTHER));
        assertEquals(32, state.completed.get(REQUEST_ID));
        assertEquals(1, state.applyCount);
    }

    @Test
    void repeatedRequestReturnsOriginalSuccessWithoutChangingInventoryAgain() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.EMERALD, 32),
                EmeraldDiamondExchange.InventorySlot.empty());

        EmeraldDiamondExchange.Result first = exchange.exchange(state, REQUEST_ID, 32);
        EmeraldDiamondExchange.Result retry = exchange.exchange(state, REQUEST_ID, 32);

        assertFalse(first.duplicate());
        assertTrue(retry.duplicate());
        assertEquals(EmeraldDiamondExchange.Status.COMPLETED, retry.status());
        assertEquals(0, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
        assertEquals(1, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
        assertEquals(1, state.applyCount);
    }

    @Test
    void insufficientEmeraldsNeverMutatesInventoryOrHistory() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.EMERALD, 31),
                EmeraldDiamondExchange.InventorySlot.empty());

        EmeraldDiamondExchange.Result result = exchange.exchange(state, REQUEST_ID, 32);

        assertEquals(EmeraldDiamondExchange.Status.INSUFFICIENT_EMERALDS, result.status());
        assertEquals(31, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
        assertEquals(0, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
        assertEquals(0, state.applyCount);
        assertTrue(state.completed.isEmpty());
    }

    @Test
    void fullInventoryNeverConsumesEmeraldsWhenDiamondCannotFit() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.EMERALD, 64),
                slot(EmeraldDiamondExchange.ItemKind.OTHER, 64));

        EmeraldDiamondExchange.Result result = exchange.exchange(state, REQUEST_ID, 32);

        assertEquals(EmeraldDiamondExchange.Status.INVENTORY_FULL, result.status());
        assertEquals(64, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
        assertEquals(0, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
        assertEquals(0, state.applyCount);
        assertTrue(state.completed.isEmpty());
    }

    @Test
    void fullInventoryCanUseSpaceInExistingDiamondStack() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.EMERALD, 64),
                slot(EmeraldDiamondExchange.ItemKind.DIAMOND, 63));

        EmeraldDiamondExchange.Result result = exchange.exchange(state, REQUEST_ID, 32);

        assertEquals(EmeraldDiamondExchange.Status.COMPLETED, result.status());
        assertEquals(32, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
        assertEquals(64, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
    }

    @Test
    void rejectsAnyUnpublishedRate() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.EMERALD, 64),
                EmeraldDiamondExchange.InventorySlot.empty());

        assertThrows(
                IllegalArgumentException.class, () -> exchange.exchange(state, REQUEST_ID, 16));
        assertEquals(64, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
    }

    @Test
    void rejectsReuseOfRequestIdWithAnotherRate() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.EMERALD, 64),
                EmeraldDiamondExchange.InventorySlot.empty());

        exchange.exchange(state, REQUEST_ID, 32);

        assertThrows(
                IllegalArgumentException.class, () -> exchange.exchange(state, REQUEST_ID, 64));
        assertEquals(32, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
        assertEquals(1, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
    }

    @Test
    void exchangesDiamondsForEmeraldsAndRetriesWithoutDoubleConversion() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.DIAMOND, 4),
                EmeraldDiamondExchange.InventorySlot.empty());

        EmeraldDiamondExchange.Result first =
                exchange.exchangeDiamonds(state, REQUEST_ID, 4);
        EmeraldDiamondExchange.Result retry =
                exchange.exchangeDiamonds(state, REQUEST_ID, 4);

        assertEquals(EmeraldDiamondExchange.Status.COMPLETED, first.status());
        assertEquals(64, first.emeraldCount());
        assertEquals(4, first.diamondCount());
        assertFalse(first.duplicate());
        assertTrue(retry.duplicate());
        assertEquals(64, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
        assertEquals(0, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
        assertEquals(1, state.applyCount);
    }

    @Test
    void diamondExchangeIsAtomicWhenEmeraldsCannotFit() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.DIAMOND, 2),
                slot(EmeraldDiamondExchange.ItemKind.OTHER, 64));

        EmeraldDiamondExchange.Result result =
                exchange.exchangeDiamonds(state, REQUEST_ID, 1);

        assertEquals(EmeraldDiamondExchange.Status.INVENTORY_FULL, result.status());
        assertEquals(2, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
        assertEquals(0, state.count(EmeraldDiamondExchange.ItemKind.EMERALD));
        assertEquals(0, state.applyCount);
        assertTrue(state.completed.isEmpty());
    }

    @Test
    void diamondExchangeRejectsUnpublishedCounts() {
        EmeraldDiamondExchange exchange = new EmeraldDiamondExchange();
        FakeState state = new FakeState(
                slot(EmeraldDiamondExchange.ItemKind.DIAMOND, 2),
                EmeraldDiamondExchange.InventorySlot.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> exchange.exchangeDiamonds(state, REQUEST_ID, 2));
        assertEquals(2, state.count(EmeraldDiamondExchange.ItemKind.DIAMOND));
    }

    private static EmeraldDiamondExchange.InventorySlot slot(
            EmeraldDiamondExchange.ItemKind kind, int amount) {
        return new EmeraldDiamondExchange.InventorySlot(kind, amount);
    }

    private static final class FakeState implements EmeraldDiamondExchange.PlayerState {
        private EmeraldDiamondExchange.InventorySlot[] contents;
        private final Map<UUID, Integer> completed = new HashMap<>();
        private int applyCount;

        private FakeState(EmeraldDiamondExchange.InventorySlot... contents) {
            this.contents = contents;
        }

        @Override
        public EmeraldDiamondExchange.InventorySlot[] storageContents() {
            return contents;
        }

        @Override
        public void setStorageContents(EmeraldDiamondExchange.InventorySlot[] contents) {
            this.contents = contents;
            applyCount++;
        }

        @Override
        public Integer completedEmeraldCount(UUID requestId) {
            return completed.get(requestId);
        }

        @Override
        public void markCompleted(UUID requestId, int emeraldCount) {
            completed.put(requestId, emeraldCount);
        }

        private int count(EmeraldDiamondExchange.ItemKind kind) {
            int total = 0;
            for (EmeraldDiamondExchange.InventorySlot item : contents) {
                if (item.kind() == kind) {
                    total += item.amount();
                }
            }
            return total;
        }
    }
}
