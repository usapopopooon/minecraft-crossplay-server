package net.usapo.eventbridge;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class UsapoEventBridgePlugin extends JavaPlugin {
    private static final long EXPERIENCE_FLUSH_TICKS = 5 * 20L;
    private static final long QUEST_EXPIRY_TICKS = 60 * 20L;

    private ExperienceAccumulator experience;

    @Override
    public void onEnable() {
        VoiceBonusRegistry voiceBonuses = new VoiceBonusRegistry();
        EmeraldDiamondExchange emeraldExchange = new EmeraldDiamondExchange();
        NamespacedKey exchangeHistoryKey = new NamespacedKey(this, "emerald_exchange_history");
        EmeraldExchangePublisher exchangePublisher =
                new EmeraldExchangePublisher(getLogger()::info);
        VoiceBonusCommand voiceBonusCommand = new VoiceBonusCommand(
                voiceBonuses, playerId -> getServer().getPlayer(playerId) != null);
        EmeraldDiamondCommand emeraldDiamondCommand = new EmeraldDiamondCommand(
                playerId -> getServer().getPlayer(playerId),
                (player, requestId, emeraldCount) -> emeraldExchange.exchange(
                        new EmeraldDiamondExchange.BukkitPlayerState(player, exchangeHistoryKey),
                        requestId,
                        emeraldCount),
                (requestId, player, emeraldCount, diamondCount) -> {
                    exchangePublisher.publish(requestId, player, emeraldCount, diamondCount);
                    getServer().broadcast(Component.text(player.getName(), NamedTextColor.YELLOW)
                            .append(Component.text("さんがエメラルド x" + emeraldCount))
                            .append(Component.text("を交換し、ダイヤモンド x" + diamondCount,
                                    NamedTextColor.AQUA))
                            .append(Component.text("を獲得しました!")));
                    player.sendActionBar(Component.text(
                            "交換完了: エメラルド x" + emeraldCount + " → ダイヤモンド x" + diamondCount,
                            NamedTextColor.AQUA));
                });
        MarketRepository marketRepository;
        try {
            marketRepository = new YamlMarketRepository(new File(getDataFolder(), "market.yml"));
        } catch (IOException error) {
            throw new IllegalStateException("Could not load Minecraft market escrow", error);
        }
        MarketTransferCommand marketTransferCommand = new MarketTransferCommand(
                playerId -> getServer().getPlayer(playerId),
                marketRepository,
                new NamespacedKey(this, "market_transfer_history"));
        QuestRepository questRepository;
        try {
            questRepository = new YamlQuestRepository(new File(getDataFolder(), "quest.yml"));
        } catch (IOException error) {
            throw new IllegalStateException("Could not load Minecraft quest escrow", error);
        }
        QuestStatePublisher questPublisher = new QuestStatePublisher(getLogger()::info);
        QuestActions questActions = new QuestActions(
                questRepository,
                questPublisher,
                new NamespacedKey(this, "pending_quest_submission"),
                quest -> getServer().broadcast(Component.text("クエスト #" + quest.id() + " を ")
                        .append(Component.text(quest.workerName(), NamedTextColor.YELLOW))
                        .append(Component.text("さんが達成しました: "))
                        .append(Component.text(
                                quest.requestedLabel(), NamedTextColor.AQUA))));
        QuestControlCommand questControlCommand = new QuestControlCommand(
                questActions,
                questRepository,
                playerId -> getServer().getPlayer(playerId));
        Objects.requireNonNull(getCommand("usapo-event-bridge"))
                .setExecutor(new EventBridgeCommand(
                        voiceBonusCommand,
                        emeraldDiamondCommand,
                        marketTransferCommand,
                        questControlCommand));
        ItemGachaRequestPublisher itemGachaPublisher =
                new ItemGachaRequestPublisher(getLogger()::info);
        BedrockGachaFormGateway itemGachaForms = (player, selectionHandler) -> false;
        BedrockExchangeFormGateway exchangeForms = (player, selectionHandler) -> false;
        BedrockMarketFormGateway marketForms = (player, actionHandler) -> false;
        BedrockQuestFormGateway questForms = (player, actionHandler) -> false;
        if (getServer().getPluginManager().isPluginEnabled("floodgate")) {
            itemGachaForms = new FloodgateGachaFormGateway(this);
            exchangeForms = new FloodgateExchangeFormGateway(this);
            marketForms = new FloodgateMarketFormGateway(this, marketRepository);
            questForms = new FloodgateQuestFormGateway(this, questRepository);
        } else {
            getLogger().warning(
                    "Floodgate is unavailable; /gacha, /exchange, /market, and /quest remain available "
                            + "without Bedrock forms");
        }
        ItemGachaCommand itemGachaCommand =
                new ItemGachaCommand(itemGachaPublisher, itemGachaForms);
        PluginCommand gacha = Objects.requireNonNull(getCommand("gacha"));
        gacha.setExecutor(itemGachaCommand);
        gacha.setTabCompleter(itemGachaCommand);
        ExchangeCommand exchangeCommand = new ExchangeCommand(
                new ExchangeRequestPublisher(getLogger()::info), exchangeForms);
        PluginCommand exchange = Objects.requireNonNull(getCommand("exchange"));
        exchange.setExecutor(exchangeCommand);
        exchange.setTabCompleter(exchangeCommand);
        MarketRequestPublisher marketPublisher = new MarketRequestPublisher(getLogger()::info);
        MarketCommand marketCommand = new MarketCommand(
                marketRepository,
                marketPublisher,
                marketForms,
                new NamespacedKey(this, "pending_market_escrow"));
        PluginCommand market = Objects.requireNonNull(getCommand("market"));
        market.setExecutor(marketCommand);
        market.setTabCompleter(marketCommand);
        getServer().getPluginManager().registerEvents(marketCommand, this);
        marketRepository.activeListings().forEach(marketPublisher::publishListing);
        getServer().getOnlinePlayers().forEach(marketCommand::recoverPendingEscrow);
        QuestCommand questCommand = new QuestCommand(
                questRepository,
                questActions,
                questForms,
                new NamespacedKey(this, "quest_draft"),
                new NamespacedKey(this, "pending_quest_reward"),
                new NamespacedKey(this, "quest_claim_history"));
        PluginCommand quest = Objects.requireNonNull(getCommand("quest"));
        quest.setExecutor(questCommand);
        quest.setTabCompleter(questCommand);
        getServer().getPluginManager().registerEvents(questCommand, this);
        questActions.recoverPendingNotifications();
        questRepository.nonterminalQuests()
                .forEach(activeQuest -> questActions.publishPersisted(activeQuest, "snapshot"));
        getServer().getOnlinePlayers().forEach(player -> {
            if (!questCommand.recoverPendingReward(player)) {
                questActions.recoverPendingSubmission(player);
            }
        });
        getServer()
                .getScheduler()
                .runTaskTimer(
                        this,
                        () -> {
                            try {
                                questActions.expire(System.currentTimeMillis());
                                questActions.recoverPendingNotifications();
                                getServer().getOnlinePlayers().forEach(questCommand::deliverNotices);
                            } catch (RuntimeException error) {
                                getLogger().severe("Could not expire quests: " + error.getMessage());
                            }
                        },
                        QUEST_EXPIRY_TICKS,
                        QUEST_EXPIRY_TICKS);
        if (!BonusToggle.isEnabled(System.getenv("USAPO_BONUSES_ENABLED"))) {
            getLogger().warning(
                    "Fishing, woodcutting, mining, natural experience, and voice XP bonuses disabled");
            return;
        }

        ActivityPublisher publisher = new EventLogPublisher(getLogger()::info);
        experience = new ExperienceAccumulator(publisher);
        getServer()
                .getPluginManager()
                .registerEvents(new ActivityListener(publisher, experience, voiceBonuses), this);
        getServer()
                .getScheduler()
                .runTaskTimer(
                        this,
                        experience::flushAll,
                        EXPERIENCE_FLUSH_TICKS,
                        EXPERIENCE_FLUSH_TICKS);
        getLogger().info("Fishing, woodcutting, mining, and experience event bridge enabled");
    }

    @Override
    public void onDisable() {
        if (experience != null) {
            experience.flushAll();
        }
    }
}
