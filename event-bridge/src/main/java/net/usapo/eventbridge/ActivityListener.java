package net.usapo.eventbridge;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class ActivityListener implements Listener {
    private static final Set<Material> WOODCUTTING_BLOCKS = EnumSet.of(
            Material.OAK_LOG,
            Material.SPRUCE_LOG,
            Material.BIRCH_LOG,
            Material.JUNGLE_LOG,
            Material.ACACIA_LOG,
            Material.DARK_OAK_LOG,
            Material.MANGROVE_LOG,
            Material.CHERRY_LOG,
            Material.PALE_OAK_LOG,
            Material.STRIPPED_OAK_LOG,
            Material.STRIPPED_SPRUCE_LOG,
            Material.STRIPPED_BIRCH_LOG,
            Material.STRIPPED_JUNGLE_LOG,
            Material.STRIPPED_ACACIA_LOG,
            Material.STRIPPED_DARK_OAK_LOG,
            Material.STRIPPED_MANGROVE_LOG,
            Material.STRIPPED_CHERRY_LOG,
            Material.STRIPPED_PALE_OAK_LOG,
            Material.CRIMSON_STEM,
            Material.WARPED_STEM,
            Material.STRIPPED_CRIMSON_STEM,
            Material.STRIPPED_WARPED_STEM);

    private final ActivityPublisher publisher;
    private final ExperienceAccumulator experience;
    private final VoiceBonusRegistry voiceBonuses;
    private final MiningBonus.Rewarder miningRewarder;
    private final MiningBonus.EligibilityChecker miningEligibility;

    ActivityListener(
            ActivityPublisher publisher,
            ExperienceAccumulator experience,
            VoiceBonusRegistry voiceBonuses) {
        this(publisher, experience, voiceBonuses, MiningBonus::award, MiningBonus::isEligible);
    }

    ActivityListener(
            ActivityPublisher publisher,
            ExperienceAccumulator experience,
            VoiceBonusRegistry voiceBonuses,
            MiningBonus.Rewarder miningRewarder,
            MiningBonus.EligibilityChecker miningEligibility) {
        this.publisher = publisher;
        this.experience = experience;
        this.voiceBonuses = voiceBonuses;
        this.miningRewarder = miningRewarder;
        this.miningEligibility = miningEligibility;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        if (!event.isCancelled() && event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            publisher.publish(ActivityKind.FISHING, event.getPlayer(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.isCancelled() && WOODCUTTING_BLOCKS.contains(event.getBlock().getType())) {
            publisher.publish(ActivityKind.WOODCUTTING, event.getPlayer(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.isCancelled()
                && WOODCUTTING_BLOCKS.contains(event.getBlockPlaced().getType())) {
            publisher.publish(ActivityKind.WOODCUTTING_RESET, event.getPlayer(), 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMining(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Material material = event.getBlock().getType();
        MiningBonus.Reward reward = MiningBonus.rewardFor(material);
        if (reward == null) {
            return;
        }
        if (!miningEligibility.isEligible(event.getPlayer(), event.getBlock())) {
            return;
        }
        int awardedExperience = voiceBonuses.isActive(event.getPlayer().getUniqueId())
                ? saturatingDouble(reward.experience())
                : reward.experience();
        experience.add(event.getPlayer(), reward.experience());
        miningRewarder.award(event.getPlayer(), reward, awardedExperience);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExperience(PlayerExpChangeEvent event) {
        int originalAmount = event.getAmount();
        if (originalAmount <= 0) {
            return;
        }
        experience.add(event.getPlayer(), originalAmount);
        if (voiceBonuses.isActive(event.getPlayer().getUniqueId())) {
            event.setAmount(saturatingDouble(originalAmount));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        experience.flush(event.getPlayer());
        voiceBonuses.deactivate(event.getPlayer().getUniqueId());
    }

    private static int saturatingDouble(int amount) {
        return amount > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : amount * 2;
    }
}
