package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.junit.jupiter.api.Test;

final class ActivityListenerTest {
    private final List<PublishedActivity> published = new ArrayList<>();
    private final VoiceBonusRegistry voiceBonuses = new VoiceBonusRegistry();
    private final ActivityPublisher publisher = (kind, player, amount) ->
            published.add(new PublishedActivity(kind, player, amount));
    private final ExperienceAccumulator experience = new ExperienceAccumulator(publisher);
    private final ActivityListener listener =
            new ActivityListener(publisher, experience, voiceBonuses);
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

    private static Block block(Material material) {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[] {Block.class},
                (proxy, method, arguments) -> method.getName().equals("getType")
                        ? material
                        : EventLogPublisherTest.defaultValue(method.getReturnType()));
    }

    private record PublishedActivity(ActivityKind kind, Player player, int amount) {}
}
