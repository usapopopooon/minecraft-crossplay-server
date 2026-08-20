package net.usapo.eventbridge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class QuestControlCommand implements CommandExecutor {
    static final String RESULT_PREFIX = "USAPO_QUEST_ACTION_RESULT|1|";

    private final QuestActions actions;
    private final QuestRepository repository;
    private final Function<UUID, Player> playerLookup;

    QuestControlCommand(
            QuestActions actions,
            QuestRepository repository,
            Function<UUID, Player> playerLookup) {
        this.actions = actions;
        this.repository = repository;
        this.playerLookup = playerLookup;
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
}
