package net.usapo.eventbridge;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

final class QuestActions {
    private final QuestRepository repository;
    private final QuestStateSink states;
    private final NamespacedKey pendingSubmissionKey;
    private final Consumer<QuestListing> completionBroadcast;

    QuestActions(
            QuestRepository repository,
            QuestStateSink states,
            NamespacedKey pendingSubmissionKey,
            Consumer<QuestListing> completionBroadcast) {
        this.repository = repository;
        this.states = states;
        this.pendingSubmissionKey = pendingSubmissionKey;
        this.completionBroadcast = completionBroadcast;
    }

    QuestTransition accept(
            long questId,
            UUID transitionId,
            UUID workerId,
            String workerName,
            long nowMillis) {
        QuestListing quest = require(questId);
        boolean replay = repository.isProcessed(questId, transitionId, "accepted");
        if (!replay) {
            if (quest.status() != QuestListing.Status.OPEN
                    || quest.openExpiresAtMillis() <= nowMillis) {
                throw failure("unavailable", "そのクエストは募集を終了しています。");
            }
            if (quest.ownerId().equals(workerId)) {
                throw failure("own_quest", "自分が依頼したクエストは受注できません。");
            }
        }
        try {
            QuestTransition transition =
                    repository.accept(questId, transitionId, workerId, workerName, nowMillis);
            publish(transition, transitionId, "accepted");
            return transition;
        } catch (IOException error) {
            throw failure("storage_error", "クエストを保存できませんでした。");
        } catch (IllegalStateException error) {
            throw failure("unavailable", "そのクエストは受注できません。");
        }
    }

    QuestTransition abandon(
            long questId, UUID transitionId, UUID workerId, long nowMillis) {
        QuestListing quest = require(questId);
        boolean replay = repository.isProcessed(questId, transitionId, "abandoned");
        if (!replay
                && (quest.status() != QuestListing.Status.ACCEPTED
                        || !workerId.equals(quest.workerId()))) {
            throw failure("not_assignee", "そのクエストの担当者ではありません。");
        }
        try {
            QuestTransition transition =
                    repository.abandon(questId, transitionId, workerId, nowMillis);
            publish(transition, transitionId, "abandoned");
            return transition;
        } catch (IOException error) {
            throw failure("storage_error", "クエストを保存できませんでした。");
        } catch (IllegalStateException error) {
            throw failure("not_assignee", "そのクエストは辞退できません。");
        }
    }

    QuestTransition cancel(long questId, UUID transitionId, UUID ownerId) {
        QuestListing quest = require(questId);
        boolean replay = repository.isProcessed(questId, transitionId, "cancelled");
        if (!replay
                && (!quest.ownerId().equals(ownerId)
                        || quest.status() != QuestListing.Status.OPEN)) {
            throw failure("not_cancellable", "募集中の自分のクエストだけ取り消せます。");
        }
        try {
            QuestTransition transition = repository.cancel(questId, transitionId, ownerId);
            publish(transition, transitionId, "cancelled");
            return transition;
        } catch (IOException error) {
            throw failure("storage_error", "クエストを保存できませんでした。");
        } catch (IllegalStateException error) {
            throw failure("not_cancellable", "そのクエストは取り消せません。");
        }
    }

    QuestTransition invalidate(long questId, UUID transitionId, UUID ownerId) {
        QuestListing quest = require(questId);
        boolean replay = repository.isProcessed(questId, transitionId, "invalidated");
        if (!quest.ownerId().equals(ownerId)) {
            throw failure("not_cancellable", "そのクエストは無効化できません。");
        }
        if (replay
                || quest.status() == QuestListing.Status.COMPLETED
                || quest.status() == QuestListing.Status.CANCELLED) {
            QuestTransition unchanged = new QuestTransition(quest, true);
            publish(unchanged, transitionId, "invalidated");
            return unchanged;
        }
        try {
            QuestTransition transition = repository.invalidate(questId, transitionId, ownerId);
            publish(transition, transitionId, "invalidated");
            return transition;
        } catch (IOException error) {
            throw failure("storage_error", "クエストを保存できませんでした。");
        } catch (IllegalStateException error) {
            throw failure("not_cancellable", "そのクエストは無効化できません。");
        }
    }

    QuestTransition releaseAssignment(
            long questId, UUID transitionId, UUID workerId, long nowMillis) {
        QuestListing quest = require(questId);
        boolean replay = repository.isProcessed(questId, transitionId, "abandoned");
        if (replay
                || quest.status() != QuestListing.Status.ACCEPTED
                || !workerId.equals(quest.workerId())) {
            QuestTransition unchanged = new QuestTransition(quest, true);
            publish(unchanged, transitionId, "abandoned");
            return unchanged;
        }
        return abandon(questId, transitionId, workerId, nowMillis);
    }

    QuestTransition submit(long questId, UUID transitionId, Player worker, long nowMillis) {
        if (recoverPendingSubmission(worker)) {
            throw failure("pending_recovered", "前回の納品処理を復旧しました。もう一度お試しください。");
        }
        QuestListing quest = require(questId);
        if (repository.isProcessed(questId, transitionId, "completed")) {
            QuestTransition duplicate = new QuestTransition(quest, true);
            publish(duplicate, transitionId, "completed");
            publishPendingCompletionBroadcasts();
            return duplicate;
        }
        if (!quest.lastTransitionId().equals(transitionId)) {
            if (quest.status() != QuestListing.Status.ACCEPTED
                    || !worker.getUniqueId().equals(quest.workerId())) {
                throw failure("not_assignee", "そのクエストの担当者ではありません。");
            }
            if (quest.acceptedDeadlineMillis() <= nowMillis) {
                throw failure("expired", "納品期限を過ぎています。");
            }
        }
        ItemStack held = worker.getInventory().getItemInMainHand();
        if (!QuestItems.matchesRequested(quest, held)) {
            throw failure(
                    "item_mismatch",
                    "メインハンドに " + quest.requestedLabel() + " 以上をまとめて持ってください。");
        }
        ItemStack submitted = QuestItems.removeRequested(held, quest.requestedCount());
        PendingQuestSubmission pending =
                new PendingQuestSubmission(transitionId, questId, submitted);
        worker.getPersistentDataContainer()
                .set(pendingSubmissionKey, PersistentDataType.STRING, pending.encode());
        removeFromMainHand(worker, held, quest.requestedCount());
        try {
            worker.saveData();
        } catch (RuntimeException error) {
            restorePendingSubmission(worker, pending);
            throw failure("storage_error", "納品アイテムを保存できませんでした。");
        }

        QuestTransition transition;
        try {
            transition = repository.complete(
                    questId, transitionId, worker.getUniqueId(), submitted, nowMillis);
        } catch (IOException | IllegalStateException error) {
            restorePendingSubmission(worker, pending);
            throw failure("storage_error", "納品を確定できませんでした。");
        }
        clearPendingSubmission(worker);
        try {
            worker.saveData();
        } catch (RuntimeException ignored) {
            // クエスト側は確定済み。保存済みマーカーが残っても復旧時に状態を見て安全に除去する。
        }
        publish(transition, transitionId, "completed");
        publishPendingCompletionBroadcasts();
        return transition;
    }

    boolean recoverPendingSubmission(Player player) {
        String encoded = player.getPersistentDataContainer()
                .get(pendingSubmissionKey, PersistentDataType.STRING);
        if (encoded == null) {
            return false;
        }
        PendingQuestSubmission pending;
        try {
            pending = PendingQuestSubmission.decode(encoded);
        } catch (IllegalArgumentException error) {
            player.sendMessage("納品の復旧データが壊れています。管理者へご連絡ください。");
            return true;
        }
        QuestListing quest = repository.find(pending.questId()).orElse(null);
        if (quest != null
                && quest.status() == QuestListing.Status.COMPLETED
                && transitionWasApplied(quest, pending.transitionId())) {
            clearPendingSubmission(player);
            try {
                player.saveData();
            } catch (RuntimeException ignored) {
                // 次回も同じ完成状態を確認して安全に除去できる。
            }
            publishPersisted(quest, "snapshot");
            publishPendingCompletionBroadcasts();
            player.sendMessage("保存途中だった納品完了データを復旧しました: #" + quest.id());
            return true;
        }
        if (quest != null
                && quest.status() == QuestListing.Status.ACCEPTED
                && player.getUniqueId().equals(quest.workerId())) {
            try {
                QuestTransition transition = repository.complete(
                        quest.id(),
                        pending.transitionId(),
                        player.getUniqueId(),
                        pending.item(),
                        System.currentTimeMillis());
                clearPendingSubmission(player);
                player.saveData();
                publish(transition, pending.transitionId(), "completed");
                publishPendingCompletionBroadcasts();
                player.sendMessage("保存途中だった納品を復旧しました: #" + quest.id());
                return true;
            } catch (IOException | RuntimeException error) {
                // 期限切れや保存失敗では、下で安全にアイテムを返す。
            }
        }
        if (!restorePendingSubmission(player, pending)) {
            player.sendMessage("納品アイテムを返す空きがありません。空きを作って /quest を再実行してください。");
        } else {
            player.sendMessage("完了していない納品アイテムを手元へ戻しました。");
        }
        return true;
    }

    void expire(long nowMillis) {
        try {
            for (QuestTransition transition : repository.expire(nowMillis)) {
                String kind = transition.quest().status() == QuestListing.Status.OPEN
                        ? "reopened"
                        : "expired";
                publish(transition, transition.quest().lastTransitionId(), kind);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not expire quests", error);
        }
    }

    void recoverPendingNotifications() {
        for (QuestStatePublication publication : repository.pendingStatePublications()) {
            publishPendingState(publication);
        }
        publishPendingCompletionBroadcasts();
    }

    boolean publishPersisted(QuestListing quest, String fallbackKind) {
        QuestStatePublication pending = repository.pendingStatePublications().stream()
                .filter(publication -> publication.quest().id() == quest.id()
                        && publication.transitionId().equals(quest.lastTransitionId()))
                .findFirst()
                .orElse(null);
        if (pending != null) {
            return publishPendingState(pending);
        }
        try {
            states.publish(quest, fallbackKind);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private QuestListing require(long questId) {
        return repository.find(questId)
                .orElseThrow(() -> failure("unknown", "そのクエストは見つかりません。"));
    }

    private void publish(QuestTransition transition, UUID requestedTransition, String kind) {
        String fallback = !transition.duplicate()
                        || transition.quest().lastTransitionId().equals(requestedTransition)
                ? kind
                : "snapshot";
        publishPersisted(transition.quest(), fallback);
    }

    private boolean publishPendingState(QuestStatePublication publication) {
        try {
            states.publish(publication.quest(), publication.transitionKind());
            repository.markStatePublished(
                    publication.quest().id(), publication.transitionId());
            return true;
        } catch (IOException | RuntimeException ignored) {
            // 状態と未送信記録は同じYAMLに保存済み。次回の復旧処理で再送する。
            return false;
        }
    }

    private void publishPendingCompletionBroadcasts() {
        for (QuestListing quest : repository.pendingCompletionBroadcasts()) {
            try {
                completionBroadcast.accept(quest);
                repository.markCompletionBroadcasted(quest.id(), quest.lastTransitionId());
            } catch (IOException | RuntimeException ignored) {
                // 達成状態と未放送記録は同じYAMLに保存済み。次回の復旧処理で再送する。
            }
        }
    }

    private static boolean transitionWasApplied(QuestListing quest, UUID transitionId) {
        return quest.lastTransitionId().equals(transitionId);
    }

    private boolean restorePendingSubmission(Player player, PendingQuestSubmission pending) {
        if (!MarketItems.canFit(player.getInventory(), pending.item())) {
            return false;
        }
        ItemStack[] snapshot = MarketItems.snapshot(player.getInventory());
        clearPendingSubmission(player);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(pending.item());
        if (!leftovers.isEmpty()) {
            player.getInventory().setStorageContents(snapshot);
            player.getPersistentDataContainer()
                    .set(pendingSubmissionKey, PersistentDataType.STRING, pending.encode());
            return false;
        }
        try {
            player.saveData();
        } catch (RuntimeException ignored) {
            // メモリ上は「返却済み・マーカーなし」。旧playerデータでは「未返却・マーカーあり」で安全。
        }
        return true;
    }

    private void clearPendingSubmission(Player player) {
        player.getPersistentDataContainer().remove(pendingSubmissionKey);
    }

    private static void removeFromMainHand(Player player, ItemStack held, int count) {
        int remaining = held.getAmount() - count;
        if (remaining == 0) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        ItemStack changed = held.clone();
        changed.setAmount(remaining);
        player.getInventory().setItemInMainHand(changed);
    }

    private static QuestActionException failure(String code, String message) {
        return new QuestActionException(code, message);
    }
}
