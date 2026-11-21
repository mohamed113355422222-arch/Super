package com.supershade.domain.notification

import android.service.notification.StatusBarNotification
import com.supershade.domain.notification.model.ShadeCategory
import com.supershade.domain.notification.model.ShadeNotification
import com.supershade.domain.notification.model.toShadeNotification
import com.supershade.settings.ClassificationMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DismissedNotificationRecord(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val dismissedTime: Long = System.currentTimeMillis(),
    val category: ShadeCategory = ShadeCategory.All,
    val isClearable: Boolean = true,
    val channelId: String? = null,
)

data class SnoozeRecord(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val untilTimestamp: Long,
    val shadeNotification: ShadeNotification? = null,
)

class NotificationRepository(
    val adaptiveLoop: AdaptivePriorityLoop? = null,
) {

    companion object {
        /**
         * Packages for voice recorder and audio recording tools.
         * Their ongoing recording notifications are routed to the Media Card instead of the feed.
         */
        val VOICE_RECORDER_PACKAGES = setOf(
            "com.sec.android.app.voicenote",
            "com.samsung.android.voicenote",
            "com.samsung.android.app.voicerecorder",
            "com.samsung.android.voiceserviceplatform",
            "com.google.android.apps.recorder",
        )

        private val MEDIA_ONLY_PACKAGES = VOICE_RECORDER_PACKAGES

        /**
         * Returns true if [sbn] is an ongoing notification from a known media/recording app.
         */
        private fun isMediaOnlyNotification(sbn: android.service.notification.StatusBarNotification): Boolean {
            if (sbn.packageName !in MEDIA_ONLY_PACKAGES) return false
            val flags = sbn.notification?.flags ?: return false
            return (flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
        }

        private const val DISMISS_TOMBSTONE_TTL_MS = 4_000L
    }

    private val categoryEngine = CategoryEngine()
    private var currentClassificationMode: ClassificationMode = ClassificationMode.ONE_UI
    private var currentAppOverrides: Map<String, ShadeCategory> = emptyMap()
    private val activeSbns = java.util.concurrent.ConcurrentHashMap<String, StatusBarNotification>()

    private val _notifications = MutableStateFlow<List<ShadeNotification>>(emptyList())
    val notifications: StateFlow<List<ShadeNotification>> = _notifications.asStateFlow()

    fun updateClassificationConfig(mode: ClassificationMode, overrides: Map<String, ShadeCategory>) {
        currentClassificationMode = mode
        currentAppOverrides = overrides
        _notifications.update { current ->
            val updated = current.map { note ->
                val sbn = activeSbns[note.key]
                val newCategory = if (sbn != null) {
                    categoryEngine.categorize(sbn, mode, overrides, adaptiveLoop)
                } else {
                    overrides[note.packageName] ?: note.category
                }
                note.copy(category = newCategory)
            }
            sortNotifications(updated)
        }
    }

    private val _dismissedHistory = MutableStateFlow<List<DismissedNotificationRecord>>(emptyList())
    val dismissedHistory: StateFlow<List<DismissedNotificationRecord>> = _dismissedHistory.asStateFlow()

    private val _voiceRecorderNotification = MutableStateFlow<ShadeNotification?>(null)
    val voiceRecorderNotification: StateFlow<ShadeNotification?> = _voiceRecorderNotification.asStateFlow()

    private val _newNotifications = MutableSharedFlow<ShadeNotification>(extraBufferCapacity = 16)
    val newNotifications: SharedFlow<ShadeNotification> = _newNotifications.asSharedFlow()

    // Pinned notifications: user-selected alerts anchored at top of feed, immune to Clear all
    private val _pinnedKeys = MutableStateFlow<Set<String>>(emptySet())
    val pinnedKeys: StateFlow<Set<String>> = _pinnedKeys.asStateFlow()

    // Accidental dismiss undo buffer (holds last user-dismissed notification for instant restore)
    private val _lastDismissed = MutableStateFlow<ShadeNotification?>(null)
    val lastDismissed: StateFlow<ShadeNotification?> = _lastDismissed.asStateFlow()

    // Smart Snoozed records
    private val _snoozedRecords = MutableStateFlow<List<SnoozeRecord>>(emptyList())
    val snoozedRecords: StateFlow<List<SnoozeRecord>> = _snoozedRecords.asStateFlow()

    // Set by NotificationCollector when the listener service is connected.
    var canceller: ((String) -> Unit)? = null
    var batchCanceller: ((List<String>) -> Unit)? = null
    var snoozer: ((String, Long) -> Unit)? = null
    var clearAller: (() -> Unit)? = null

    private data class DismissedTombstone(
        val postTime: Long,
        val expiresAtMs: Long,
    )

    // key → timestamp when snooze expires; in-memory only, reset on service restart
    private val snoozedUntil = mutableMapOf<String, Long>()
    // keys recently restored via undoLastDismiss so refresh() doesn't immediately prune them
    private val recentUndoKeys = mutableMapOf<String, Long>()
    // keys recently dismissed so in-flight NMS syncActiveNotifications / updates don't resurrect them
    private val recentDismissedKeys = java.util.concurrent.ConcurrentHashMap<String, DismissedTombstone>()

    private fun sortNotifications(list: List<ShadeNotification>): List<ShadeNotification> {
        val pinned = _pinnedKeys.value
        return list.sortedWith(
            compareByDescending<ShadeNotification> { it.key in pinned }
                .thenByDescending { it.postTime }
        )
    }

    fun togglePin(key: String) {
        val willPin = key !in _pinnedKeys.value
        _pinnedKeys.update { current ->
            if (key in current) current - key else current + key
        }
        _notifications.update { sortNotifications(it) }
        val note = _notifications.value.find { it.key == key }
        if (note != null) {
            adaptiveLoop?.recordInteraction(
                note.packageName,
                note.channelId,
                if (willPin) NotificationInteractionType.PIN else NotificationInteractionType.UNPIN,
            )
        }
    }

    fun isPinned(key: String): Boolean = key in _pinnedKeys.value

    fun snooze(key: String, delayMs: Long) {
        val until = System.currentTimeMillis() + delayMs
        snoozedUntil[key] = until
        val note = _notifications.value.find { it.key == key }
        if (note != null) {
            adaptiveLoop?.recordInteraction(
                note.packageName,
                note.channelId,
                NotificationInteractionType.SNOOZE,
            )
            val record = SnoozeRecord(
                key = key,
                packageName = note.packageName,
                title = note.title,
                text = note.text,
                untilTimestamp = until,
                shadeNotification = note,
            )
            _snoozedRecords.update { current ->
                listOf(record) + current.filter { it.key != key }
            }
        }
        snoozer?.invoke(key, delayMs)
        onNotificationRemoved(key)
    }

    fun unsnooze(key: String) {
        val record = _snoozedRecords.value.find { it.key == key }
        snoozedUntil.remove(key)
        _snoozedRecords.update { it.filter { r -> r.key != key } }
        if (record?.shadeNotification != null) {
            _notifications.update { current ->
                sortNotifications(listOf(record.shadeNotification) + current.filter { it.key != key })
            }
        } else {
            refresh()
        }
    }

    private fun isSnoozed(key: String): Boolean {
        val until = snoozedUntil[key] ?: return false
        return if (System.currentTimeMillis() < until) true
        else {
            snoozedUntil.remove(key)
            _snoozedRecords.update { it.filter { r -> r.key != key } }
            false
        }
    }

    fun undoLastDismiss() {
        val note = _lastDismissed.value ?: return
        _lastDismissed.value = null
        recentDismissedKeys.remove(note.key)
        recentUndoKeys[note.key] = System.currentTimeMillis() + 5_000L
        _dismissedHistory.update { current -> current.filter { it.key != note.key } }
        _notifications.update { current ->
            sortNotifications(listOf(note) + current.filter { it.key != note.key })
        }
    }

    fun clearLastDismissed() {
        _lastDismissed.value = null
    }

    fun refresh() {
        com.supershade.service.NotificationCollector.instance?.refreshNotifications()
    }

    /**
     * Synchronizes repository state with the live system active notifications list:
     * posts/updates present notifications and prunes stale notifications whose keys
     * are no longer active in Android's NotificationManager.
     */
    fun syncActiveNotifications(
        activeList: List<StatusBarNotification>,
        rankingMap: android.service.notification.NotificationListenerService.RankingMap? = null,
    ) {
        val now = System.currentTimeMillis()
        recentUndoKeys.entries.removeIf { it.value <= now }
        recentDismissedKeys.entries.removeIf { it.value.expiresAtMs <= now }
        val activeKeySet = activeList.mapTo(HashSet(activeList.size)) { it.key }
        activeList.forEach { onNotificationPosted(it, rankingMap) }

        val staleKeys = activeSbns.keys.filter { it !in activeKeySet }
        staleKeys.forEach { activeSbns.remove(it) }

        _voiceRecorderNotification.value?.let { voiceNote ->
            if (voiceNote.key !in activeKeySet) {
                _voiceRecorderNotification.value = null
            }
        }
        _notifications.update { current ->
            current.filter { note ->
                val tombstone = recentDismissedKeys[note.key]
                val blockedByDismiss = tombstone != null && now < tombstone.expiresAtMs && note.postTime <= tombstone.postTime
                !blockedByDismiss && (note.key in activeKeySet || note.key in recentUndoKeys)
            }
        }
    }

    fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: android.service.notification.NotificationListenerService.RankingMap? = null,
    ) {
        val now = System.currentTimeMillis()
        val tombstone = recentDismissedKeys[sbn.key]
        if (tombstone != null) {
            if (now < tombstone.expiresAtMs && sbn.postTime <= tombstone.postTime) {
                activeSbns.remove(sbn.key)
                return
            } else {
                recentDismissedKeys.remove(sbn.key, tombstone)
            }
        }

        activeSbns[sbn.key] = sbn

        // Apps like Samsung Voice Recorder post an ongoing MediaStyle service notification
        // while also registering a MediaSession. Suppress them from the notification feed;
        // they are routed to the media card.
        if (isMediaOnlyNotification(sbn)) {
            val category = categoryEngine.categorize(sbn, currentClassificationMode, currentAppOverrides, adaptiveLoop)
            val shade = sbn.toShadeNotification(category, rankingMap)
            _voiceRecorderNotification.value = shade
            return
        }

        val category = categoryEngine.categorize(sbn, currentClassificationMode, currentAppOverrides, adaptiveLoop)
        val shade = sbn.toShadeNotification(category, rankingMap)

        if (shade.title.isBlank() && shade.text.isBlank()) return
        if (isSnoozed(shade.key)) return

        // Capture whether this key is genuinely new BEFORE updating the list.
        val isNew = _notifications.value.none { it.key == shade.key }

        _notifications.update { current ->
            val without = current.filter { it.key != shade.key }
            // If this is a group summary and we already have child notifications for
            // this group, omit the summary — children carry all the visible content.
            if (shade.isGroupSummary && !shade.groupKey.isNullOrBlank() && without.any {
                    it.packageName == shade.packageName &&
                    it.groupKey == shade.groupKey &&
                    !it.isGroupSummary
                }) {
                without
            } else {
                val cleaned = if (!shade.isGroupSummary && !shade.groupKey.isNullOrBlank()) {
                    without.filterNot {
                        it.isGroupSummary &&
                            it.packageName == shade.packageName &&
                            it.groupKey == shade.groupKey
                    }
                } else {
                    without
                }
                sortNotifications(listOf(shade) + cleaned)
            }
        }

        // Only show a heads-up peek card for genuinely new, non-summary, non-ongoing notifications.
        // Updates to existing notifications or ongoing services shouldn't spam toasts.
        if (isNew && !shade.isGroupSummary && !shade.isOngoing && shade.packageName != "com.supershade") {
            _newNotifications.tryEmit(shade)
        }
    }

    fun onNotificationRemoved(key: String) {
        activeSbns.remove(key)
        if (_voiceRecorderNotification.value?.key == key) {
            _voiceRecorderNotification.value = null
        }
        val removed = _notifications.value.find { it.key == key }
        if (removed != null && removed.packageName != "com.supershade") {
            val record = DismissedNotificationRecord(
                key = removed.key,
                packageName = removed.packageName,
                title = removed.title,
                text = removed.text,
                postTime = removed.postTime,
                dismissedTime = System.currentTimeMillis(),
                category = removed.category,
                isClearable = removed.isClearable,
                channelId = removed.channelId,
            )
            _dismissedHistory.update { current ->
                (listOf(record) + current.filter { it.key != key }).take(60)
            }
        }
        _notifications.update { current -> current.filter { it.key != key } }
    }

    private fun cancelOrphanGroupSummaries(
        affectedGroups: Set<Pair<String, String>>,
        alreadyCancelledKeys: Set<String>,
    ) {
        if (affectedGroups.isEmpty()) return
        val remaining = _notifications.value
        val orphanSummaryKeys = mutableListOf<String>()
        for ((pkg, grp) in affectedGroups) {
            val hasRemainingChild = remaining.any {
                it.packageName == pkg && it.groupKey == grp && !it.isGroupSummary
            }
            if (!hasRemainingChild) {
                activeSbns.values
                    .filter { sbn ->
                        sbn.packageName == pkg &&
                            sbn.notification.group == grp &&
                            (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0 &&
                            sbn.isClearable &&
                            sbn.key !in alreadyCancelledKeys
                    }
                    .forEach { summarySbn ->
                        orphanSummaryKeys.add(summarySbn.key)
                    }
            }
        }
        if (orphanSummaryKeys.isNotEmpty()) {
            val tombstoneExpiry = System.currentTimeMillis() + DISMISS_TOMBSTONE_TTL_MS
            orphanSummaryKeys.forEach { key ->
                val postTime = activeSbns.remove(key)?.postTime ?: Long.MAX_VALUE
                recentDismissedKeys[key] = DismissedTombstone(postTime = postTime, expiresAtMs = tombstoneExpiry)
            }
            if (batchCanceller != null) {
                batchCanceller?.invoke(orphanSummaryKeys)
            } else {
                orphanSummaryKeys.forEach { k -> canceller?.invoke(k) }
            }
            _notifications.update { current -> current.filterNot { it.key in orphanSummaryKeys } }
        }
    }

    fun cancelAndRemove(key: String): Boolean {
        val target = _notifications.value.find { it.key == key }
        if (target != null && !target.isClearable) {
            return false
        }
        val now = System.currentTimeMillis()
        recentUndoKeys.remove(key)
        val dismissedPostTime = target?.postTime ?: activeSbns[key]?.postTime ?: now
        recentDismissedKeys[key] = DismissedTombstone(
            postTime = dismissedPostTime,
            expiresAtMs = now + DISMISS_TOMBSTONE_TTL_MS,
        )
        if (target != null) {
            _lastDismissed.value = target
            val elapsed = now - target.postTime
            val isFast = elapsed in 0..4000L
            adaptiveLoop?.recordInteraction(
                target.packageName,
                target.channelId,
                if (isFast) NotificationInteractionType.FAST_DISMISS else NotificationInteractionType.DISMISS,
            )
        }
        canceller?.invoke(key)
        onNotificationRemoved(key)
        val groupKey = target?.groupKey
        if (target != null && !groupKey.isNullOrBlank()) {
            cancelOrphanGroupSummaries(setOf(target.packageName to groupKey), setOf(key))
        }
        return true
    }

    fun cancelGroup(keys: List<String>): Int {
        if (keys.isEmpty()) return 0
        val keySet = keys.toSet()
        val dismissible = _notifications.value.filter { it.key in keySet && it.isClearable }
        if (dismissible.isEmpty()) return 0

        val dismissibleKeys = dismissible.map { it.key }
        val dismissibleKeySet = dismissibleKeys.toSet()
        dismissibleKeys.forEach { recentUndoKeys.remove(it) }
        _lastDismissed.value = dismissible.first()

        val now = System.currentTimeMillis()
        val tombstoneExpiry = now + DISMISS_TOMBSTONE_TTL_MS
        val newRecords = dismissible
            .filter { it.packageName != "com.supershade" }
            .map { removed ->
                DismissedNotificationRecord(
                    key = removed.key,
                    packageName = removed.packageName,
                    title = removed.title,
                    text = removed.text,
                    postTime = removed.postTime,
                    dismissedTime = now,
                    category = removed.category,
                    isClearable = removed.isClearable,
                    channelId = removed.channelId,
                )
            }

        dismissible.forEach { target ->
            recentDismissedKeys[target.key] = DismissedTombstone(target.postTime, tombstoneExpiry)
            activeSbns.remove(target.key)
            if (_voiceRecorderNotification.value?.key == target.key) {
                _voiceRecorderNotification.value = null
            }
            val elapsed = now - target.postTime
            val isFast = elapsed in 0..4000L
            adaptiveLoop?.recordInteraction(
                target.packageName,
                target.channelId,
                if (isFast) NotificationInteractionType.FAST_DISMISS else NotificationInteractionType.DISMISS,
            )
        }

        if (newRecords.isNotEmpty()) {
            _dismissedHistory.update { current ->
                (newRecords + current).distinctBy { it.key }.take(60)
            }
        }
        _notifications.update { current -> current.filterNot { it.key in dismissibleKeySet } }

        if (batchCanceller != null) {
            batchCanceller?.invoke(dismissibleKeys)
        } else {
            dismissibleKeys.forEach { k -> canceller?.invoke(k) }
        }

        val affectedGroups = dismissible
            .mapNotNull { n -> n.groupKey?.takeIf { it.isNotBlank() }?.let { n.packageName to it } }
            .toSet()
        cancelOrphanGroupSummaries(affectedGroups, dismissibleKeySet)

        return dismissible.size
    }

    fun cancelAll() {
        _lastDismissed.value = null
        val now = System.currentTimeMillis()
        val pinned = _pinnedKeys.value
        // Pinned and ongoing notifications are immune to Clear all
        val dismissible = _notifications.value.filter { it.isClearable && it.key !in pinned }
        if (dismissible.isEmpty()) return

        val dismissibleKeys = dismissible.map { it.key }
        val dismissibleKeySet = dismissibleKeys.toSet()
        dismissibleKeys.forEach { recentUndoKeys.remove(it) }
        val tombstoneExpiry = now + DISMISS_TOMBSTONE_TTL_MS

        val newRecords = dismissible
            .filter { it.packageName != "com.supershade" }
            .map { note ->
                DismissedNotificationRecord(
                    key = note.key,
                    packageName = note.packageName,
                    title = note.title,
                    text = note.text,
                    postTime = note.postTime,
                    dismissedTime = now,
                    category = note.category,
                    isClearable = note.isClearable,
                    channelId = note.channelId,
                )
            }
        if (newRecords.isNotEmpty()) {
            _dismissedHistory.update { current ->
                (newRecords + current).distinctBy { it.key }.take(60)
            }
        }
        dismissible.forEach { note ->
            recentDismissedKeys[note.key] = DismissedTombstone(note.postTime, tombstoneExpiry)
            activeSbns.remove(note.key)
            adaptiveLoop?.recordInteraction(note.packageName, note.channelId, NotificationInteractionType.CLEAR_ALL)
        }
        _notifications.update { current -> current.filter { !it.isClearable || it.key in pinned } }

        if (pinned.isEmpty() && clearAller != null) {
            clearAller?.invoke()
        } else if (batchCanceller != null) {
            batchCanceller?.invoke(dismissibleKeys)
        } else {
            dismissibleKeys.forEach { key -> canceller?.invoke(key) }
        }

        val affectedGroups = dismissible
            .mapNotNull { n -> n.groupKey?.takeIf { it.isNotBlank() }?.let { n.packageName to it } }
            .toSet()
        cancelOrphanGroupSummaries(affectedGroups, dismissibleKeySet)
    }

    fun recordLaunch(note: ShadeNotification) {
        adaptiveLoop?.recordInteraction(
            note.packageName,
            note.channelId,
            NotificationInteractionType.CLICK,
        )
    }

    fun recordReply(note: ShadeNotification) {
        adaptiveLoop?.recordInteraction(
            note.packageName,
            note.channelId,
            NotificationInteractionType.REPLY,
        )
    }

    fun clearAll() {
        val pinned = _pinnedKeys.value
        _notifications.update { current -> current.filter { it.key in pinned } }
    }

    fun clearDismissedHistory() {
        _dismissedHistory.value = emptyList()
    }
}
