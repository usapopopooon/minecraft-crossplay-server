package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MaterialBuybackExchangeTest {
    private static final UUID REQUEST_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000061");

    @Test
    void removesOnlyPlainMatchingItemsAndDeduplicatesTheRequest() {
        FakeState state = new FakeState(
                slot("minecraft:sand", 32, true),
                slot("minecraft:sand", 64, false),
                slot("minecraft:dirt", 64, true),
                slot("minecraft:sand", 32, true));
        MaterialBuybackExchange exchange = new MaterialBuybackExchange();

        MaterialBuybackExchange.Result first =
                exchange.exchange(state, REQUEST_ID, "minecraft:sand", 64);
        MaterialBuybackExchange.Result duplicate =
                exchange.exchange(state, REQUEST_ID, "minecraft:sand", 64);

        assertEquals(MaterialBuybackExchange.Status.COMPLETED, first.status());
        assertEquals(false, first.duplicate());
        assertEquals(true, duplicate.duplicate());
        assertEquals(1, state.commits);
        assertEquals(0, plainCount(state.contents, "minecraft:sand"));
        assertEquals(64, nonPlainCount(state.contents, "minecraft:sand"));
        assertEquals(64, plainCount(state.contents, "minecraft:dirt"));
    }

    @Test
    void insufficientItemsDoNotCommitAndRequestIdsCannotBeReused() {
        FakeState insufficient = new FakeState(slot("minecraft:tuff", 63, true));
        MaterialBuybackExchange exchange = new MaterialBuybackExchange();

        MaterialBuybackExchange.Result result =
                exchange.exchange(insufficient, REQUEST_ID, "minecraft:tuff", 64);
        assertEquals(MaterialBuybackExchange.Status.INSUFFICIENT_ITEMS, result.status());
        assertEquals(0, insufficient.commits);

        FakeState completed = new FakeState(slot("minecraft:tuff", 128, true));
        exchange.exchange(completed, REQUEST_ID, "minecraft:tuff", 64);
        assertThrows(
                IllegalArgumentException.class,
                () -> exchange.exchange(completed, REQUEST_ID, "minecraft:tuff", 128));
    }

    @Test
    void catalogUsesTheConfirmedGenerousRates() {
        assertEquals(
                500,
                MaterialBuybackCatalog.selection(
                                MaterialBuybackCatalog.find("minecraft:emerald").orElseThrow(), 64)
                        .expectedReward());
        assertEquals(
                30,
                MaterialBuybackCatalog.selection(
                                MaterialBuybackCatalog.find("minecraft:dirt").orElseThrow(), 64)
                        .expectedReward());
        assertEquals(
                200,
                MaterialBuybackCatalog.selection(
                                MaterialBuybackCatalog.find("minecraft:sandstone").orElseThrow(),
                                256)
                        .expectedReward());
        assertThrows(
                IllegalArgumentException.class,
                () -> MaterialBuybackCatalog.selection(
                        MaterialBuybackCatalog.find("minecraft:sand").orElseThrow(), 63));
    }

    @Test
    void quantityChoicesShowStockSeparatelyAndNeverOfferAGuaranteedLimitOverrun() {
        MaterialBuybackCatalog.Rate sandstone =
                MaterialBuybackCatalog.find("minecraft:sandstone").orElseThrow();

        assertEquals(
                List.of(64, 128, 256, 512, 1_024, 2_304),
                MaterialBuybackCatalog.quantityOptions(sandstone, 2_304).stream()
                        .map(MaterialBuybackCatalog.QuantityOption::itemCount)
                        .toList());
        assertEquals(
                "交換可能分をすべて（36スタック・2304個）",
                MaterialBuybackCatalog.quantityOptions(sandstone, 2_304)
                        .getLast()
                        .label());
        assertEquals(3_840, MaterialBuybackCatalog.maximumDailyItemCount(sandstone));

        MaterialBuybackCatalog.Rate emerald = MaterialBuybackCatalog.EMERALD_RATE;
        assertEquals(384, MaterialBuybackCatalog.maximumDailyItemCount(emerald));
        assertEquals(
                List.of(64, 128, 256, 384),
                MaterialBuybackCatalog.quantityOptions(emerald, 640).stream()
                        .map(MaterialBuybackCatalog.QuantityOption::itemCount)
                        .toList());

        MaterialBuybackCatalog.Rate dirt =
                MaterialBuybackCatalog.find("minecraft:dirt").orElseThrow();
        assertEquals(
                "交換可能分をすべて（3スタック・192個）",
                MaterialBuybackCatalog.quantityOptions(dirt, 192).getLast().label());
    }

    private static MaterialBuybackExchange.InventorySlot slot(
            String itemId, int amount, boolean plain) {
        return new MaterialBuybackExchange.InventorySlot(itemId, amount, plain);
    }

    private static int plainCount(
            MaterialBuybackExchange.InventorySlot[] slots, String itemId) {
        int total = 0;
        for (MaterialBuybackExchange.InventorySlot slot : slots) {
            if (slot.plain() && itemId.equals(slot.itemId())) {
                total += slot.amount();
            }
        }
        return total;
    }

    private static int nonPlainCount(
            MaterialBuybackExchange.InventorySlot[] slots, String itemId) {
        int total = 0;
        for (MaterialBuybackExchange.InventorySlot slot : slots) {
            if (!slot.plain() && itemId.equals(slot.itemId())) {
                total += slot.amount();
            }
        }
        return total;
    }

    private static final class FakeState implements MaterialBuybackExchange.PlayerState {
        private MaterialBuybackExchange.InventorySlot[] contents;
        private final Map<UUID, MaterialBuybackExchange.CompletedRequest> completed =
                new HashMap<>();
        private int commits;

        private FakeState(MaterialBuybackExchange.InventorySlot... contents) {
            this.contents = contents;
        }

        @Override
        public MaterialBuybackExchange.InventorySlot[] storageContents() {
            return contents.clone();
        }

        @Override
        public MaterialBuybackExchange.CompletedRequest completedRequest(UUID requestId) {
            return completed.get(requestId);
        }

        @Override
        public void commit(
                MaterialBuybackExchange.InventorySlot[] updated,
                UUID requestId,
                String itemId,
                int itemCount) {
            contents = updated.clone();
            completed.put(
                    requestId, new MaterialBuybackExchange.CompletedRequest(itemId, itemCount));
            commits++;
        }
    }
}
