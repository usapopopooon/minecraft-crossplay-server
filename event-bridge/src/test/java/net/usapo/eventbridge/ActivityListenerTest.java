package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.junit.jupiter.api.Test;

final class ActivityListenerTest {
    private final List<PublishedActivity> published = new ArrayList<>();
    private final VoiceBonusRegistry voiceBonuses = new VoiceBonusRegistry();
    private final ActivityPublisher publisher = (kind, player, amount) ->
            published.add(new PublishedActivity(kind, player, amount));
    private final ExperienceAccumulator experience = new ExperienceAccumulator(publisher);
    private final List<MiningAward> miningAwards = new ArrayList<>();
    private final ActivityListener listener = new ActivityListener(
            publisher,
            experience,
            voiceBonuses,
            (player, reward, awardedExperience) ->
                    miningAwards.add(new MiningAward(player, reward, awardedExperience)),
            (candidate, block) -> candidate.getGameMode() == GameMode.SURVIVAL
                    && block.isPreferredTool(null));
    private final Player player = player();

    @Test
    void caughtFishIsPublishedForTheCorrectPlayer() {
        PlayerFishEvent event =
                new PlayerFishEvent(player, null, null, PlayerFishEvent.State.CAUGHT_FISH);

        listener.onFishing(event);

        assertEquals(List.of(new PublishedActivity(ActivityKind.FISHING, player, 1)), published);
    }

    @Test
    void nonCatchAndCancelledFishingEventsAreIgnored() {
        listener.onFishing(new PlayerFishEvent(player, null, null, PlayerFishEvent.State.FISHING));
        PlayerFishEvent cancelled =
                new PlayerFishEvent(player, null, null, PlayerFishEvent.State.CAUGHT_FISH);
        cancelled.setCancelled(true);
        listener.onFishing(cancelled);

        assertEquals(List.of(), published);
    }

    @Test
    void supportedLogIsPublishedForTheCorrectPlayer() {
        listener.onBlockBreak(new BlockBreakEvent(block(Material.STRIPPED_PALE_OAK_LOG), player));

        assertEquals(
                List.of(new PublishedActivity(ActivityKind.WOODCUTTING, player, 1)), published);
    }

    @Test
    void planksAndCancelledLogBreaksAreIgnored() {
        listener.onBlockBreak(new BlockBreakEvent(block(Material.OAK_PLANKS), player));
        BlockBreakEvent cancelled = new BlockBreakEvent(block(Material.OAK_LOG), player);
        cancelled.setCancelled(true);
        listener.onBlockBreak(cancelled);

        assertEquals(List.of(), published);
    }

    @Test
    void placingSupportedLogPublishesWoodcuttingResetForTheBuilder() {
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getBlockPlaced()).thenReturn(block(Material.OAK_LOG));
        when(event.getPlayer()).thenReturn(player);

        listener.onBlockPlace(event);

        assertEquals(
                List.of(new PublishedActivity(ActivityKind.WOODCUTTING_RESET, player, 1)),
                published);
    }

    @Test
    void placingPlanksOrCancelledLogDoesNotResetWoodcutting() {
        BlockPlaceEvent planks = mock(BlockPlaceEvent.class);
        when(planks.getBlockPlaced()).thenReturn(block(Material.OAK_PLANKS));
        when(planks.getPlayer()).thenReturn(player);
        BlockPlaceEvent cancelledLog = mock(BlockPlaceEvent.class);
        when(cancelledLog.getBlockPlaced()).thenReturn(block(Material.OAK_LOG));
        when(cancelledLog.getPlayer()).thenReturn(player);
        when(cancelledLog.isCancelled()).thenReturn(true);

        listener.onBlockPlace(planks);
        listener.onBlockPlace(cancelledLog);

        assertEquals(List.of(), published);
    }

    @Test
    void eligibleOreAwardsTheCorrectPlayerAndPublishesItsBaseExperience() {
        Player miner = miningPlayer(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                GameMode.SURVIVAL);

        listener.onMining(new BlockBreakEvent(block(Material.DIAMOND_ORE, true), miner));
        experience.flushAll();

        MiningBonus.Reward reward = MiningBonus.rewardFor(Material.DIAMOND_ORE);
        assertEquals(List.of(new MiningAward(miner, reward, 50)), miningAwards);
        assertEquals(
                List.of(new PublishedActivity(ActivityKind.EXPERIENCE, miner, 50)), published);
    }

    @Test
    void voiceBonusDoublesMiningRewardButPublishesOnlyItsBaseExperience() {
        Player miner = miningPlayer(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                GameMode.SURVIVAL);
        voiceBonuses.activate(miner.getUniqueId());

        listener.onMining(new BlockBreakEvent(block(Material.ANCIENT_DEBRIS, true), miner));
        experience.flushAll();

        MiningBonus.Reward reward = MiningBonus.rewardFor(Material.ANCIENT_DEBRIS);
        assertEquals(List.of(new MiningAward(miner, reward, 200)), miningAwards);
        assertEquals(
                List.of(new PublishedActivity(ActivityKind.EXPERIENCE, miner, 100)), published);
    }

    @Test
    void ineligibleAndCancelledOreBreaksDoNotAwardOrPublishExperience() {
        Player creativeMiner = miningPlayer(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                GameMode.CREATIVE);
        Player survivalMiner = miningPlayer(
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                GameMode.SURVIVAL);

        listener.onMining(
                new BlockBreakEvent(block(Material.DIAMOND_ORE, true), creativeMiner));
        listener.onMining(
                new BlockBreakEvent(block(Material.DIAMOND_ORE, false), survivalMiner));
        BlockBreakEvent cancelled =
                new BlockBreakEvent(block(Material.DIAMOND_ORE, true), survivalMiner);
        cancelled.setCancelled(true);
        listener.onMining(cancelled);
        experience.flushAll();

        assertEquals(List.of(), miningAwards);
        assertEquals(List.of(), published);
    }

    @Test
    void naturalExperiencePublishesOriginalAmountWithoutChangingStandardReward() {
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 7);

        listener.onExperience(event);
        experience.flushAll();

        assertEquals(7, event.getAmount());
        assertEquals(
                List.of(new PublishedActivity(ActivityKind.EXPERIENCE, player, 7)), published);
    }

    @Test
    void voiceBonusDoublesNaturalExperienceButPublishesOnlyOriginalAmount() {
        voiceBonuses.activate(player.getUniqueId());
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 7);

        listener.onExperience(event);
        experience.flushAll();

        assertEquals(14, event.getAmount());
        assertEquals(
                List.of(new PublishedActivity(ActivityKind.EXPERIENCE, player, 7)), published);
    }

    @Test
    void nonPositiveExperienceIsIgnoredAndDeactivatedBonusStopsDoubling() {
        voiceBonuses.activate(player.getUniqueId());
        listener.onExperience(new PlayerExpChangeEvent(player, 0));
        voiceBonuses.deactivate(player.getUniqueId());
        PlayerExpChangeEvent afterQuit = new PlayerExpChangeEvent(player, 7);

        listener.onExperience(afterQuit);
        experience.flushAll();

        assertEquals(7, afterQuit.getAmount());
        assertEquals(
                List.of(new PublishedActivity(ActivityKind.EXPERIENCE, player, 7)), published);
    }

    private static Player player() {
        UUID uniqueId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "Steve";
                    case "getUniqueId" -> uniqueId;
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }

    private static Player miningPlayer(UUID uniqueId, GameMode gameMode) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "Miner";
                    case "getUniqueId" -> uniqueId;
                    case "getGameMode" -> gameMode;
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }

    private static Block block(Material material) {
        return block(material, false);
    }

    private static Block block(Material material, boolean preferredTool) {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[] {Block.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getType" -> material;
                    case "isPreferredTool" -> preferredTool;
                    default -> EventLogPublisherTest.defaultValue(method.getReturnType());
                });
    }

    private record PublishedActivity(ActivityKind kind, Player player, int amount) {}

    private record MiningAward(
            Player player, MiningBonus.Reward reward, int awardedExperience) {}
}
