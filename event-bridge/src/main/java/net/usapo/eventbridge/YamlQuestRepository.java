package net.usapo.eventbridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

final class YamlQuestRepository implements QuestRepository {
    private static final long OPEN_LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1_000;

    private final File file;
    private final Map<Long, QuestListing> quests = new LinkedHashMap<>();
    private final Map<UUID, QuestClaim> claims = new LinkedHashMap<>();
    private final Map<UUID, QuestNotice> notices = new LinkedHashMap<>();
    private final Map<UUID, ProcessedTransition> transitions = new LinkedHashMap<>();
    private final Map<Long, PendingStatePublication> pendingStatePublications =
            new LinkedHashMap<>();
    private final Map<Long, UUID> pendingCompletionBroadcasts = new LinkedHashMap<>();
    private long nextId = 1;

    YamlQuestRepository(File file) throws IOException {
        this.file = file;
        load();
    }

    @Override
    public synchronized QuestListing create(
            UUID eventId,
            UUID ownerId,
            String ownerName,
            String requestedItemId,
            String requestedItemName,
            int requestedCount,
            int fulfillmentHours,
            ItemStack reward,
            long nowMillis)
            throws IOException {
        return create(
                eventId,
                ownerId,
                ownerName,
                requestedItemId,
                requestedItemName,
                null,
                requestedCount,
                fulfillmentHours,
                reward,
                nowMillis);
    }

    @Override
    public synchronized QuestListing create(
            UUID eventId,
            UUID ownerId,
            String ownerName,
            String requestedItemId,
            String requestedItemName,
            ItemStack requestedItem,
            int requestedCount,
            int fulfillmentHours,
            ItemStack reward,
            long nowMillis)
            throws IOException {
        QuestListing existing = quests.values().stream()
                .filter(quest -> quest.eventId().equals(eventId))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (!existing.ownerId().equals(ownerId)
                    || !existing.ownerName().equals(ownerName)
                    || !existing.requestedItemId().equals(requestedItemId)
                    || !existing.requestedItemName().equals(requestedItemName)
                    || !java.util.Objects.equals(existing.requestedItem(), requestedItem)
                    || existing.requestedCount() != requestedCount
                    || existing.fulfillmentHours() != fulfillmentHours
                    || !existing.reward().equals(reward)) {
                throw new IllegalStateException("quest creation event conflict");
            }
            return existing;
        }
        long id = nextId;
        QuestListing quest = new QuestListing(
                id,
                eventId,
                ownerId,
                ownerName,
                requestedItemId,
                requestedItemName,
                requestedItem,
                requestedCount,
                fulfillmentHours,
                reward,
                QuestListing.Status.OPEN,
                null,
                null,
                nowMillis,
                nowMillis + OPEN_LIFETIME_MILLIS,
                0,
                eventId);
        quests.put(id, quest);
        transitions.put(eventId, new ProcessedTransition(id, "created"));
        pendingStatePublications.put(id, new PendingStatePublication(eventId, "created"));
        nextId++;
        try {
            save();
        } catch (IOException error) {
            quests.remove(id);
            transitions.remove(eventId);
            pendingStatePublications.remove(id);
            nextId = id;
            throw error;
        }
        return quest;
    }

    @Override
    public synchronized Optional<QuestListing> find(long questId) {
        return Optional.ofNullable(quests.get(questId));
    }

    @Override
    public synchronized Optional<QuestListing> findByEventId(UUID eventId) {
        return quests.values().stream()
                .filter(quest -> quest.eventId().equals(eventId))
                .findFirst();
    }

    @Override
    public synchronized List<QuestListing> openQuests() {
        return quests.values().stream()
                .filter(quest -> quest.status() == QuestListing.Status.OPEN)
                .sorted(Comparator.comparingLong(QuestListing::id).reversed())
                .toList();
    }

    @Override
    public synchronized List<QuestListing> nonterminalQuests() {
        return quests.values().stream()
                .filter(quest -> quest.status() == QuestListing.Status.OPEN
                        || quest.status() == QuestListing.Status.ACCEPTED)
                .sorted(Comparator.comparingLong(QuestListing::id).reversed())
                .toList();
    }

    @Override
    public synchronized List<QuestListing> activeFor(UUID playerId) {
        return quests.values().stream()
                .filter(quest -> (quest.status() == QuestListing.Status.OPEN
                                && quest.ownerId().equals(playerId))
                        || (quest.status() == QuestListing.Status.ACCEPTED
                                && (quest.ownerId().equals(playerId)
                                        || playerId.equals(quest.workerId()))))
                .sorted(Comparator.comparingLong(QuestListing::id).reversed())
                .toList();
    }

    @Override
    public synchronized List<QuestStatePublication> pendingStatePublications() {
        return pendingStatePublications.entrySet().stream()
                .map(entry -> new QuestStatePublication(
                        requireQuest(entry.getKey()),
                        entry.getValue().transitionId(),
                        entry.getValue().kind()))
                .toList();
    }

    @Override
    public synchronized void markStatePublished(long questId, UUID transitionId)
            throws IOException {
        PendingStatePublication pending = pendingStatePublications.get(questId);
        if (pending == null || !pending.transitionId().equals(transitionId)) {
            return;
        }
        pendingStatePublications.remove(questId);
        try {
            save();
        } catch (IOException error) {
            pendingStatePublications.put(questId, pending);
            throw error;
        }
    }

    @Override
    public synchronized List<QuestListing> pendingCompletionBroadcasts() {
        return pendingCompletionBroadcasts.entrySet().stream()
                .filter(entry -> requireQuest(entry.getKey())
                        .lastTransitionId()
                        .equals(entry.getValue()))
                .map(entry -> requireQuest(entry.getKey()))
                .toList();
    }

    @Override
    public synchronized void markCompletionBroadcasted(long questId, UUID transitionId)
            throws IOException {
        UUID pending = pendingCompletionBroadcasts.get(questId);
        if (!transitionId.equals(pending)) {
            return;
        }
        pendingCompletionBroadcasts.remove(questId);
        try {
            save();
        } catch (IOException error) {
            pendingCompletionBroadcasts.put(questId, pending);
            throw error;
        }
    }

    @Override
    public synchronized boolean isProcessed(long questId, UUID transitionId, String kind) {
        return isDuplicate(questId, transitionId, kind);
    }

    @Override
    public synchronized QuestTransition accept(
            long questId,
            UUID transitionId,
            UUID workerId,
            String workerName,
            long nowMillis)
            throws IOException {
        QuestListing current = requireQuest(questId);
        if (isDuplicate(questId, transitionId, "accepted")) {
            return new QuestTransition(current, true);
        }
        if (current.status() != QuestListing.Status.OPEN
                || current.openExpiresAtMillis() <= nowMillis
                || current.ownerId().equals(workerId)) {
            throw new IllegalStateException("quest is not available");
        }
        QuestListing changed = copy(
                current,
                QuestListing.Status.ACCEPTED,
                workerId,
                workerName,
                current.openExpiresAtMillis(),
                nowMillis + current.fulfillmentHours() * 60L * 60 * 1_000,
                transitionId);
        replaceAndSave(current, changed, "accepted");
        return new QuestTransition(changed, false);
    }

    @Override
    public synchronized QuestTransition abandon(
            long questId, UUID transitionId, UUID workerId, long nowMillis) throws IOException {
        QuestListing current = requireQuest(questId);
        if (isDuplicate(questId, transitionId, "abandoned")) {
            return new QuestTransition(current, true);
        }
        if (current.status() != QuestListing.Status.ACCEPTED
                || !workerId.equals(current.workerId())) {
            throw new IllegalStateException("quest is not assigned to worker");
        }
        QuestListing changed = copy(
                current,
                QuestListing.Status.OPEN,
                null,
                null,
                nowMillis + OPEN_LIFETIME_MILLIS,
                0,
                transitionId);
        replaceAndSave(current, changed, "abandoned");
        return new QuestTransition(changed, false);
    }

    @Override
    public synchronized QuestTransition cancel(
            long questId, UUID transitionId, UUID ownerId) throws IOException {
        QuestListing current = requireQuest(questId);
        if (isDuplicate(questId, transitionId, "cancelled")) {
            return new QuestTransition(current, true);
        }
        if (current.status() != QuestListing.Status.OPEN || !ownerId.equals(current.ownerId())) {
            throw new IllegalStateException("quest cannot be cancelled");
        }
        QuestListing changed = copy(
                current,
                QuestListing.Status.CANCELLED,
                null,
                null,
                current.openExpiresAtMillis(),
                0,
                transitionId);
        QuestClaim claim = claim(transitionId, "reward", current.id(), ownerId, current.reward());
        replaceWithClaimsAndSave(current, changed, List.of(claim), "cancelled");
        return new QuestTransition(changed, false);
    }

    @Override
    public synchronized QuestTransition invalidate(
            long questId, UUID transitionId, UUID ownerId) throws IOException {
        QuestListing current = requireQuest(questId);
        if (isDuplicate(questId, transitionId, "invalidated")) {
            return new QuestTransition(current, true);
        }
        if ((current.status() != QuestListing.Status.OPEN
                        && current.status() != QuestListing.Status.ACCEPTED)
                || !ownerId.equals(current.ownerId())) {
            throw new IllegalStateException("quest cannot be invalidated");
        }
        QuestListing changed = copy(
                current,
                QuestListing.Status.CANCELLED,
                null,
                null,
                current.openExpiresAtMillis(),
                0,
                transitionId);
        QuestClaim claim = claim(transitionId, "reward", current.id(), ownerId, current.reward());
        replaceWithClaimsAndSave(current, changed, List.of(claim), "invalidated");
        return new QuestTransition(changed, false);
    }

    @Override
    public synchronized QuestTransition complete(
            long questId,
            UUID transitionId,
            UUID workerId,
            ItemStack submittedItem,
            long nowMillis)
            throws IOException {
        QuestListing current = requireQuest(questId);
        if (isDuplicate(questId, transitionId, "completed")) {
            return new QuestTransition(current, true);
        }
        if (current.status() != QuestListing.Status.ACCEPTED
                || !workerId.equals(current.workerId())
                || current.acceptedDeadlineMillis() <= nowMillis
                || !QuestItems.matchesRequested(current, submittedItem)
                || submittedItem.getAmount() != current.requestedCount()) {
            throw new IllegalStateException("quest submission does not match");
        }
        QuestListing changed = copy(
                current,
                QuestListing.Status.COMPLETED,
                current.workerId(),
                current.workerName(),
                current.openExpiresAtMillis(),
                current.acceptedDeadlineMillis(),
                transitionId);
        QuestClaim ownerClaim = claim(
                transitionId, "submission", current.id(), current.ownerId(), submittedItem);
        QuestClaim workerClaim = claim(
                transitionId, "reward", current.id(), workerId, current.reward());
        replaceWithClaimsAndSave(
                current, changed, List.of(ownerClaim, workerClaim), "completed");
        return new QuestTransition(changed, false);
    }

    @Override
    public synchronized List<QuestTransition> expire(long nowMillis) throws IOException {
        List<QuestTransition> transitions = new ArrayList<>();
        for (QuestListing current : new ArrayList<>(quests.values())) {
            if (current.status() == QuestListing.Status.OPEN
                    && current.openExpiresAtMillis() <= nowMillis) {
                UUID transitionId = UUID.randomUUID();
                QuestListing changed = copy(
                        current,
                        QuestListing.Status.CANCELLED,
                        null,
                        null,
                        current.openExpiresAtMillis(),
                        0,
                        transitionId);
                QuestClaim reward = claim(
                        transitionId, "reward", current.id(), current.ownerId(), current.reward());
                QuestNotice ownerNotice = notice(
                        transitionId,
                        "open-expired",
                        current.id(),
                        current.ownerId(),
                        "クエスト #" + current.id()
                                + " は募集期限切れで終了しました。報酬は受取箱へ戻しました。");
                replaceWithClaimsAndNoticesAndSave(
                        current, changed, List.of(reward), List.of(ownerNotice), "expired");
                transitions.add(new QuestTransition(changed, false));
            } else if (current.status() == QuestListing.Status.ACCEPTED
                    && current.acceptedDeadlineMillis() <= nowMillis) {
                UUID transitionId = UUID.randomUUID();
                QuestListing changed = copy(
                        current,
                        QuestListing.Status.OPEN,
                        null,
                        null,
                        nowMillis + OPEN_LIFETIME_MILLIS,
                        0,
                        transitionId);
                QuestNotice ownerNotice = notice(
                        transitionId,
                        "assignment-expired-owner",
                        current.id(),
                        current.ownerId(),
                        "クエスト #" + current.id() + " は受注者の納品期限切れにより再募集しました。");
                QuestNotice workerNotice = notice(
                        transitionId,
                        "assignment-expired-worker",
                        current.id(),
                        current.workerId(),
                        "クエスト #" + current.id()
                                + " の納品期限が切れたため、受注を解除して再募集しました。");
                replaceWithClaimsAndNoticesAndSave(
                        current,
                        changed,
                        List.of(),
                        List.of(ownerNotice, workerNotice),
                        "reopened");
                transitions.add(new QuestTransition(changed, false));
            }
        }
        return transitions;
    }

    @Override
    public synchronized List<QuestClaim> pendingClaims(UUID ownerId) {
        return claims.values().stream()
                .filter(claim -> claim.ownerId().equals(ownerId)
                        && claim.status() != QuestClaim.Status.DELIVERED)
                .toList();
    }

    @Override
    public synchronized List<QuestNotice> pendingNotices(UUID playerId) {
        return notices.values().stream()
                .filter(notice -> notice.playerId().equals(playerId))
                .toList();
    }

    @Override
    public synchronized void acknowledgeNotice(UUID noticeId, UUID playerId) throws IOException {
        QuestNotice notice = notices.get(noticeId);
        if (notice == null || !notice.playerId().equals(playerId)) {
            return;
        }
        notices.remove(noticeId);
        try {
            save();
        } catch (IOException error) {
            notices.put(noticeId, notice);
            throw error;
        }
    }

    @Override
    public synchronized QuestClaim prepareClaim(
            UUID claimId, UUID ownerId, UUID transferId) throws IOException {
        QuestClaim current = requireClaim(claimId, ownerId);
        if (current.status() == QuestClaim.Status.DELIVERING
                && transferId.equals(current.transferId())) {
            return current;
        }
        if (current.status() != QuestClaim.Status.PENDING) {
            throw new IllegalStateException("claim is not pending");
        }
        QuestClaim changed = new QuestClaim(
                current.id(),
                current.questId(),
                current.ownerId(),
                current.item(),
                QuestClaim.Status.DELIVERING,
                transferId);
        replaceClaimAndSave(current, changed);
        return changed;
    }

    @Override
    public synchronized QuestClaim completeClaim(
            UUID claimId, UUID ownerId, UUID transferId) throws IOException {
        QuestClaim current = requireClaim(claimId, ownerId);
        if (current.status() == QuestClaim.Status.DELIVERED
                && transferId.equals(current.transferId())) {
            return current;
        }
        if (current.status() != QuestClaim.Status.DELIVERING
                || !transferId.equals(current.transferId())) {
            throw new IllegalStateException("claim transfer does not match");
        }
        QuestClaim changed = new QuestClaim(
                current.id(),
                current.questId(),
                current.ownerId(),
                current.item(),
                QuestClaim.Status.DELIVERED,
                transferId);
        replaceClaimAndSave(current, changed);
        return changed;
    }

    @Override
    public synchronized void abortClaim(UUID claimId, UUID ownerId, UUID transferId)
            throws IOException {
        QuestClaim current = requireClaim(claimId, ownerId);
        if (current.status() != QuestClaim.Status.DELIVERING
                || !transferId.equals(current.transferId())) {
            return;
        }
        QuestClaim changed = new QuestClaim(
                current.id(),
                current.questId(),
                current.ownerId(),
                current.item(),
                QuestClaim.Status.PENDING,
                null);
        replaceClaimAndSave(current, changed);
    }

    private QuestListing requireQuest(long questId) {
        QuestListing quest = quests.get(questId);
        if (quest == null) {
            throw new IllegalArgumentException("unknown quest");
        }
        return quest;
    }

    private QuestClaim requireClaim(UUID claimId, UUID ownerId) {
        QuestClaim claim = claims.get(claimId);
        if (claim == null || !claim.ownerId().equals(ownerId)) {
            throw new IllegalArgumentException("unknown quest claim");
        }
        return claim;
    }

    private void replaceAndSave(QuestListing previous, QuestListing changed, String kind)
            throws IOException {
        PendingStatePublication previousPublication = pendingStatePublications.get(changed.id());
        quests.put(changed.id(), changed);
        transitions.put(
                changed.lastTransitionId(), new ProcessedTransition(changed.id(), kind));
        pendingStatePublications.put(
                changed.id(), new PendingStatePublication(changed.lastTransitionId(), kind));
        try {
            save();
        } catch (IOException error) {
            quests.put(previous.id(), previous);
            transitions.remove(changed.lastTransitionId());
            restorePendingState(previous.id(), previousPublication);
            throw error;
        }
    }

    private void replaceWithClaimsAndSave(
            QuestListing previous,
            QuestListing changed,
            List<QuestClaim> addedClaims,
            String kind)
            throws IOException {
        replaceWithClaimsAndNoticesAndSave(previous, changed, addedClaims, List.of(), kind);
    }

    private void replaceWithClaimsAndNoticesAndSave(
            QuestListing previous,
            QuestListing changed,
            List<QuestClaim> addedClaims,
            List<QuestNotice> addedNotices,
            String kind)
            throws IOException {
        PendingStatePublication previousPublication = pendingStatePublications.get(changed.id());
        UUID previousBroadcast = pendingCompletionBroadcasts.get(changed.id());
        quests.put(changed.id(), changed);
        transitions.put(
                changed.lastTransitionId(), new ProcessedTransition(changed.id(), kind));
        pendingStatePublications.put(
                changed.id(), new PendingStatePublication(changed.lastTransitionId(), kind));
        if (kind.equals("completed")) {
            pendingCompletionBroadcasts.put(changed.id(), changed.lastTransitionId());
        }
        addedClaims.forEach(claim -> claims.put(claim.id(), claim));
        addedNotices.forEach(notice -> notices.put(notice.id(), notice));
        try {
            save();
        } catch (IOException error) {
            quests.put(previous.id(), previous);
            transitions.remove(changed.lastTransitionId());
            restorePendingState(previous.id(), previousPublication);
            restorePendingBroadcast(previous.id(), previousBroadcast);
            addedClaims.forEach(claim -> claims.remove(claim.id()));
            addedNotices.forEach(notice -> notices.remove(notice.id()));
            throw error;
        }
    }

    private void replaceClaimAndSave(QuestClaim previous, QuestClaim changed) throws IOException {
        claims.put(changed.id(), changed);
        try {
            save();
        } catch (IOException error) {
            claims.put(previous.id(), previous);
            throw error;
        }
    }

    private void load() throws IOException {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, yaml.getLong("next-id", 1));
        ConfigurationSection questRoot = yaml.getConfigurationSection("quests");
        if (questRoot != null) {
            for (String key : questRoot.getKeys(false)) {
                try {
                    QuestListing quest = readQuest(yaml, key);
                    quests.put(quest.id(), quest);
                    nextId = Math.max(nextId, quest.id() + 1);
                } catch (IllegalArgumentException error) {
                    throw new IOException("invalid quest " + key, error);
                }
            }
        }
        ConfigurationSection claimRoot = yaml.getConfigurationSection("claims");
        if (claimRoot != null) {
            for (String key : claimRoot.getKeys(false)) {
                try {
                    QuestClaim claim = readClaim(yaml, key);
                    claims.put(claim.id(), claim);
                } catch (IllegalArgumentException error) {
                    throw new IOException("invalid quest claim " + key, error);
                }
            }
        }
        ConfigurationSection noticeRoot = yaml.getConfigurationSection("notices");
        if (noticeRoot != null) {
            for (String key : noticeRoot.getKeys(false)) {
                try {
                    String path = "notices." + key + ".";
                    QuestNotice notice = new QuestNotice(
                            UUID.fromString(key),
                            yaml.getLong(path + "quest-id"),
                            UUID.fromString(yaml.getString(path + "player-id", "")),
                            yaml.getString(path + "message", ""));
                    notices.put(notice.id(), notice);
                } catch (IllegalArgumentException error) {
                    throw new IOException("invalid quest notice " + key, error);
                }
            }
        }
        ConfigurationSection transitionRoot = yaml.getConfigurationSection("transitions");
        if (transitionRoot != null) {
            for (String key : transitionRoot.getKeys(false)) {
                try {
                    ConfigurationSection entry = transitionRoot.getConfigurationSection(key);
                    if (entry == null) {
                        throw new IllegalArgumentException("missing quest transition entry");
                    }
                    transitions.put(
                            UUID.fromString(key),
                            new ProcessedTransition(
                                    entry.getLong("quest-id"), entry.getString("kind", "")));
                } catch (IllegalArgumentException error) {
                    throw new IOException("invalid quest transition " + key, error);
                }
            }
        }
        quests.values().forEach(quest -> transitions.putIfAbsent(
                quest.lastTransitionId(),
                new ProcessedTransition(quest.id(), inferredKind(quest.status()))));
        ConfigurationSection publicationRoot =
                yaml.getConfigurationSection("pending-state-publications");
        if (publicationRoot != null) {
            for (String key : publicationRoot.getKeys(false)) {
                try {
                    long questId = Long.parseLong(key);
                    ConfigurationSection entry = publicationRoot.getConfigurationSection(key);
                    if (entry == null) {
                        throw new IllegalArgumentException("missing pending publication entry");
                    }
                    PendingStatePublication publication = new PendingStatePublication(
                            UUID.fromString(entry.getString("transition-id", "")),
                            entry.getString("kind", ""));
                    QuestListing quest = requireQuest(questId);
                    if (!quest.lastTransitionId().equals(publication.transitionId())) {
                        throw new IllegalArgumentException("stale pending publication");
                    }
                    pendingStatePublications.put(questId, publication);
                } catch (IllegalArgumentException error) {
                    throw new IOException("invalid pending quest publication " + key, error);
                }
            }
        }
        ConfigurationSection broadcastRoot =
                yaml.getConfigurationSection("pending-completion-broadcasts");
        if (broadcastRoot != null) {
            for (String key : broadcastRoot.getKeys(false)) {
                try {
                    long questId = Long.parseLong(key);
                    UUID transitionId = UUID.fromString(broadcastRoot.getString(key, ""));
                    QuestListing quest = requireQuest(questId);
                    if (quest.status() != QuestListing.Status.COMPLETED
                            || !quest.lastTransitionId().equals(transitionId)) {
                        throw new IllegalArgumentException("stale pending completion broadcast");
                    }
                    pendingCompletionBroadcasts.put(questId, transitionId);
                } catch (IllegalArgumentException error) {
                    throw new IOException("invalid pending completion broadcast " + key, error);
                }
            }
        }
    }

    private QuestListing readQuest(YamlConfiguration yaml, String key) {
        String path = "quests." + key + ".";
        ItemStack reward = yaml.getItemStack(path + "reward");
        if (reward == null) {
            throw new IllegalArgumentException("missing quest reward");
        }
        String workerId = yaml.getString(path + "worker-id");
        return new QuestListing(
                Long.parseLong(key),
                UUID.fromString(yaml.getString(path + "event-id", "")),
                UUID.fromString(yaml.getString(path + "owner-id", "")),
                yaml.getString(path + "owner-name", ""),
                yaml.getString(path + "requested-item-id", ""),
                yaml.getString(path + "requested-item-name", ""),
                yaml.getItemStack(path + "requested-item"),
                yaml.getInt(path + "requested-count"),
                yaml.getInt(path + "fulfillment-hours"),
                reward,
                QuestListing.Status.valueOf(yaml.getString(path + "status", "")),
                workerId == null ? null : UUID.fromString(workerId),
                yaml.getString(path + "worker-name"),
                yaml.getLong(path + "created-at"),
                yaml.getLong(path + "open-expires-at"),
                yaml.getLong(path + "accepted-deadline"),
                UUID.fromString(yaml.getString(path + "last-transition-id", "")));
    }

    private QuestClaim readClaim(YamlConfiguration yaml, String key) {
        String path = "claims." + key + ".";
        ItemStack item = yaml.getItemStack(path + "item");
        if (item == null) {
            throw new IllegalArgumentException("missing claim item");
        }
        String transferId = yaml.getString(path + "transfer-id");
        return new QuestClaim(
                UUID.fromString(key),
                yaml.getLong(path + "quest-id"),
                UUID.fromString(yaml.getString(path + "owner-id", "")),
                item,
                QuestClaim.Status.valueOf(yaml.getString(path + "status", "")),
                transferId == null ? null : UUID.fromString(transferId));
    }

    private void save() throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        for (QuestListing quest : quests.values()) {
            String path = "quests." + quest.id() + ".";
            yaml.set(path + "event-id", quest.eventId().toString());
            yaml.set(path + "owner-id", quest.ownerId().toString());
            yaml.set(path + "owner-name", quest.ownerName());
            yaml.set(path + "requested-item-id", quest.requestedItemId());
            yaml.set(path + "requested-item-name", quest.requestedItemName());
            yaml.set(path + "requested-item", quest.requestedItem());
            yaml.set(path + "requested-count", quest.requestedCount());
            yaml.set(path + "fulfillment-hours", quest.fulfillmentHours());
            yaml.set(path + "reward", quest.reward());
            yaml.set(path + "status", quest.status().name());
            yaml.set(path + "worker-id", optionalUuid(quest.workerId()));
            yaml.set(path + "worker-name", quest.workerName());
            yaml.set(path + "created-at", quest.createdAtMillis());
            yaml.set(path + "open-expires-at", quest.openExpiresAtMillis());
            yaml.set(path + "accepted-deadline", quest.acceptedDeadlineMillis());
            yaml.set(path + "last-transition-id", quest.lastTransitionId().toString());
        }
        for (QuestClaim claim : claims.values()) {
            String path = "claims." + claim.id() + ".";
            yaml.set(path + "quest-id", claim.questId());
            yaml.set(path + "owner-id", claim.ownerId().toString());
            yaml.set(path + "item", claim.item());
            yaml.set(path + "status", claim.status().name());
            yaml.set(path + "transfer-id", optionalUuid(claim.transferId()));
        }
        for (QuestNotice notice : notices.values()) {
            String path = "notices." + notice.id() + ".";
            yaml.set(path + "quest-id", notice.questId());
            yaml.set(path + "player-id", notice.playerId().toString());
            yaml.set(path + "message", notice.message());
        }
        transitions.forEach((transitionId, transition) -> {
            String path = "transitions." + transitionId + ".";
            yaml.set(path + "quest-id", transition.questId());
            yaml.set(path + "kind", transition.kind());
        });
        pendingStatePublications.forEach((questId, publication) -> {
            String path = "pending-state-publications." + questId + ".";
            yaml.set(path + "transition-id", publication.transitionId().toString());
            yaml.set(path + "kind", publication.kind());
        });
        pendingCompletionBroadcasts.forEach((questId, transitionId) ->
                yaml.set("pending-completion-broadcasts." + questId, transitionId.toString()));
        File temporary = parent == null
                ? new File("." + file.getName() + ".tmp")
                : new File(parent, "." + file.getName() + ".tmp");
        yaml.save(temporary);
        try {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static QuestListing copy(
            QuestListing current,
            QuestListing.Status status,
            UUID workerId,
            String workerName,
            long openExpiresAt,
            long acceptedDeadline,
            UUID transitionId) {
        return new QuestListing(
                current.id(),
                current.eventId(),
                current.ownerId(),
                current.ownerName(),
                current.requestedItemId(),
                current.requestedItemName(),
                current.requestedItem(),
                current.requestedCount(),
                current.fulfillmentHours(),
                current.reward(),
                status,
                workerId,
                workerName,
                current.createdAtMillis(),
                openExpiresAt,
                acceptedDeadline,
                transitionId);
    }

    private static QuestClaim claim(
            UUID transitionId, String purpose, long questId, UUID ownerId, ItemStack item) {
        UUID claimId = UUID.nameUUIDFromBytes(
                (transitionId + ":" + purpose).getBytes(StandardCharsets.UTF_8));
        return new QuestClaim(
                claimId, questId, ownerId, item, QuestClaim.Status.PENDING, null);
    }

    private static QuestNotice notice(
            UUID transitionId, String purpose, long questId, UUID playerId, String message) {
        UUID noticeId = UUID.nameUUIDFromBytes(
                (transitionId + ":notice:" + purpose).getBytes(StandardCharsets.UTF_8));
        return new QuestNotice(noticeId, questId, playerId, message);
    }

    private static String optionalUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private void restorePendingState(long questId, PendingStatePublication previous) {
        if (previous == null) {
            pendingStatePublications.remove(questId);
        } else {
            pendingStatePublications.put(questId, previous);
        }
    }

    private void restorePendingBroadcast(long questId, UUID previous) {
        if (previous == null) {
            pendingCompletionBroadcasts.remove(questId);
        } else {
            pendingCompletionBroadcasts.put(questId, previous);
        }
    }

    private boolean isDuplicate(long questId, UUID transitionId, String kind) {
        ProcessedTransition processed = transitions.get(transitionId);
        if (processed == null) {
            return false;
        }
        if (processed.questId() != questId || !processed.kind().equals(kind)) {
            throw new IllegalStateException("quest transition event conflict");
        }
        return true;
    }

    private static String inferredKind(QuestListing.Status status) {
        return switch (status) {
            case OPEN -> "created";
            case ACCEPTED -> "accepted";
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
        };
    }

    private record ProcessedTransition(long questId, String kind) {
        private ProcessedTransition {
            if (questId <= 0 || kind.isBlank()) {
                throw new IllegalArgumentException("invalid processed quest transition");
            }
        }
    }

    private record PendingStatePublication(UUID transitionId, String kind) {
        private PendingStatePublication {
            if (transitionId == null || kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("invalid pending quest publication");
            }
        }
    }
}
