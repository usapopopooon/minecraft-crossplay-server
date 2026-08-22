package net.usapo.eventbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

final class QuestControlCommand implements CommandExecutor {
    static final String RESULT_PREFIX = "USAPO_QUEST_ACTION_RESULT|1|";
    static final String CREATE_RESULT_PREFIX = "USAPO_QUEST_CREATE_RESULT|1|";

    private final QuestActions actions;
    private final QuestRepository repository;
    private final Function<UUID, Player> playerLookup;
    private final Function<String, ItemStack> adminItemLookup;

    QuestControlCommand(
            QuestActions actions,
            QuestRepository repository,
            Function<UUID, Player> playerLookup) {
        this(actions, repository, playerLookup, QuestControlCommand::adminItem);
    }

    QuestControlCommand(
            QuestActions actions,
            QuestRepository repository,
            Function<UUID, Player> playerLookup,
            Function<String, ItemStack> adminItemLookup) {
        this.actions = actions;
        this.repository = repository;
        this.playerLookup = playerLookup;
        this.adminItemLookup = adminItemLookup;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments) {
        if (arguments.length == 0 || !arguments[0].startsWith("quest-")) {
            return false;
        }
        if (arguments[0].equals("quest-admin-create")) {
            return createAdminQuest(sender, arguments);
        }
        UUID requestId = parseRequestId(arguments);
        long questId = parseQuestId(arguments);
        try {
            String action = arguments[0];
            UUID playerId = UUID.fromString(required(arguments, 2));
            QuestTransition transition = switch (action) {
                case "quest-accept" -> {
                    if (arguments.length != 5) {
                        throw new IllegalArgumentException("invalid argument count");
                    }
                    String playerName = decodeName(arguments[3]);
                    requestId = UUID.fromString(arguments[4]);
                    yield actions.accept(
                            questId,
                            requestId,
                            playerId,
                            playerName,
                            System.currentTimeMillis());
                }
                case "quest-submit" -> {
                    requireFour(arguments);
                    requestId = UUID.fromString(arguments[3]);
                    Player player = playerLookup.apply(playerId);
                    if (player == null || !player.isOnline()) {
                        throw new QuestActionException(
                                "player_offline", "プレイヤーがMinecraftにログインしていません。");
                    }
                    yield actions.submit(questId, requestId, player, System.currentTimeMillis());
                }
                case "quest-abandon" -> {
                    requireFour(arguments);
                    requestId = UUID.fromString(arguments[3]);
                    yield actions.releaseAssignment(
                            questId, requestId, playerId, System.currentTimeMillis());
                }
                case "quest-cancel" -> {
                    requireFour(arguments);
                    requestId = UUID.fromString(arguments[3]);
                    yield actions.cancel(questId, requestId, playerId);
                }
                case "quest-invalidate" -> {
                    requireFour(arguments);
                    requestId = UUID.fromString(arguments[3]);
                    yield actions.invalidate(questId, requestId, playerId);
                }
                default -> throw new IllegalArgumentException("unknown quest action");
            };
            result(
                    sender,
                    requestId,
                    questId,
                    "completed",
                    transition.quest().status().name().toLowerCase(),
                    transition.duplicate());
        } catch (QuestActionException error) {
            result(sender, requestId, questId, error.code(), currentStatus(questId), false);
        } catch (IllegalArgumentException error) {
            result(sender, requestId, questId, "invalid_request", currentStatus(questId), false);
        }
        return true;
    }

    private boolean createAdminQuest(CommandSender sender, String[] arguments) {
        UUID requestId = parseRequestId(arguments);
        long questId = 0;
        try {
            if (arguments.length != 7) {
                throw new IllegalArgumentException("invalid argument count");
            }
            String requestedItemId = arguments[1];
            int requestedCount = positiveInteger(arguments[2], "invalid_requested_count");
            String rewardItemId = arguments[3];
            int rewardCount = positiveInteger(arguments[4], "invalid_reward_count");
            int fulfillmentHours = positiveInteger(arguments[5], "invalid_hours");
            requestId = UUID.fromString(arguments[6]);
            ItemStack requestedItem = requiredAdminItem(
                    requestedItemId,
                    requestedCount,
                    "invalid_requested_item",
                    "invalid_requested_count");
            ItemStack reward = requiredAdminItem(
                    rewardItemId,
                    rewardCount,
                    "invalid_reward_item",
                    "invalid_reward_count");
            requestedItem.setAmount(1);
            if (fulfillmentHours > 72) {
                throw new AdminCreateException("invalid_hours");
            }
            boolean duplicate = repository.findByEventId(requestId).isPresent();
            QuestListing quest = repository.create(
                    requestId,
                    QuestIssuer.SYSTEM_ID,
                    QuestIssuer.SYSTEM_NAME,
                    requestedItem.getType().getKey().toString(),
                    MarketItems.questDisplayName(requestedItem),
                    requestedItem,
                    requestedCount,
                    fulfillmentHours,
                    reward,
                    System.currentTimeMillis());
            questId = quest.id();
            actions.publishPersisted(quest, "created");
            createResult(sender, requestId, questId, "completed", duplicate);
        } catch (AdminCreateException error) {
            createResult(sender, requestId, questId, error.status(), false);
        } catch (IOException error) {
            createResult(sender, requestId, questId, "storage_error", false);
        } catch (IllegalArgumentException | IllegalStateException error) {
            createResult(sender, requestId, questId, "invalid_request", false);
        }
        return true;
    }

    private ItemStack requiredAdminItem(
            String itemId, int count, String itemStatus, String countStatus) {
        if (!itemId.startsWith("minecraft:")) {
            throw new AdminCreateException(itemStatus);
        }
        ItemStack item = adminItemLookup.apply(itemId);
        if (!QuestItems.isSupportedAdminItem(item)) {
            throw new AdminCreateException(itemStatus);
        }
        if (count > item.getMaxStackSize()) {
            throw new AdminCreateException(countStatus);
        }
        item.setAmount(count);
        return item;
    }

    private static int positiveInteger(String value, String status) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 下の入力別エラーへ統一する。
        }
        throw new AdminCreateException(status);
    }

    private static ItemStack adminItem(String itemId) {
        Material material = Material.matchMaterial(itemId);
        return material == null || material.isAir() || !material.isItem()
                ? null
                : new ItemStack(material);
    }

    private String currentStatus(long questId) {
        return repository.find(questId)
                .map(quest -> quest.status().name().toLowerCase())
                .orElse("unknown");
    }

    private static void requireFour(String[] arguments) {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("invalid argument count");
        }
    }

    private static String required(String[] arguments, int index) {
        if (arguments.length <= index) {
            throw new IllegalArgumentException("missing argument");
        }
        return arguments[index];
    }

    private static long parseQuestId(String[] arguments) {
        try {
            long questId = Long.parseLong(required(arguments, 1));
            return questId > 0 ? questId : 0;
        } catch (IllegalArgumentException error) {
            return 0;
        }
    }

    private static UUID parseRequestId(String[] arguments) {
        if (arguments.length > 0) {
            try {
                return UUID.fromString(arguments[arguments.length - 1]);
            } catch (IllegalArgumentException ignored) {
                // 下の固定値をエラー応答の相関IDとして使う。
            }
        }
        return new UUID(0, 0);
    }

    private static String decodeName(String encoded) {
        String name;
        try {
            name = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid player name", error);
        }
        if (name.isBlank() || name.length() > 64 || name.contains("|") || name.contains("\n")) {
            throw new IllegalArgumentException("invalid player name");
        }
        return name;
    }

    private static void result(
            CommandSender sender,
            UUID requestId,
            long questId,
            String status,
            String questStatus,
            boolean duplicate) {
        sender.sendMessage(RESULT_PREFIX + requestId + "|" + questId + "|" + status + "|"
                + questStatus + "|" + (duplicate ? "duplicate" : "new"));
    }

    private static void createResult(
            CommandSender sender,
            UUID requestId,
            long questId,
            String status,
            boolean duplicate) {
        sender.sendMessage(CREATE_RESULT_PREFIX + requestId + "|" + questId + "|" + status + "|"
                + (duplicate ? "duplicate" : "new"));
    }

    private static final class AdminCreateException extends RuntimeException {
        private final String status;

        private AdminCreateException(String status) {
            this.status = status;
        }

        private String status() {
            return status;
        }
    }
}
