package com.eldering.safety.data.repository

import com.eldering.detection.DetectionEvent
import com.eldering.detection.Mode
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.DetectionEventDao
import com.eldering.safety.data.db.DetectionEventRecord
import com.eldering.safety.data.db.DetectionOutcome
import com.eldering.safety.data.db.FeedbackLabel
import kotlinx.coroutines.flow.Flow

/**
 * Local log of detector events with their features and the user's verdict (false-alarm feedback). Never uploaded
 * automatically.
 */
class DetectionEventRepository(
    private val dao: DetectionEventDao,
    private val time: TimeSource = TimeSource.System,
) {
    /**
     * Stores [event] when the countdown starts and returns its id.
     *
     * @param triggeredAtMs wall-clock time of the impact (see `core.elapsedRealtimeNanosToWallMillis`).
     * @param windowJson optional raw sensor window around the event, kept for false-alarm analysis.
     */
    suspend fun record(
        event: DetectionEvent,
        mode: Mode?,
        triggeredAtMs: Long,
        lat: Double? = null,
        lng: Double? = null,
        windowJson: String? = null,
    ): Long = dao.insert(
        DetectionEventRecord(
            triggeredAtMs = triggeredAtMs,
            sensorTimeNanos = event.triggeredAtNanos,
            kind = event.kind.name,
            confidence = event.confidence,
            reason = event.reason,
            mode = mode?.name,
            features = event.features,
            lat = lat,
            lng = lng,
            windowJson = windowJson,
        ),
    )

    suspend fun get(id: Long): DetectionEventRecord? = dao.get(id)

    fun observeRecent(limit: Int = 50): Flow<List<DetectionEventRecord>> = dao.observeRecent(limit)

    suspend fun setOutcome(id: Long, outcome: DetectionOutcome, incidentKey: String? = null) {
        dao.setOutcome(id, outcome, incidentKey)
    }

    /** Records (or clears, with null) the user's verdict on an event. */
    suspend fun label(id: Long, label: FeedbackLabel?, note: String? = null) {
        dao.setLabel(id, label, note?.trim()?.take(MAX_NOTE_LENGTH), label?.let { time.nowMillis() })
    }

    suspend fun labeled(): List<DetectionEventRecord> = dao.labeled()

    /** Unlabeled events are kept 30 days, labeled ones 180 days. */
    suspend fun prune(): Int {
        val now = time.nowMillis()
        return dao.prune(unlabeledCutoffMs = now - UNLABELED_RETENTION_MS, labeledCutoffMs = now - LABELED_RETENTION_MS)
    }

    companion object {
        const val UNLABELED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val LABELED_RETENTION_MS = 180L * 24 * 60 * 60 * 1000
        const val MAX_NOTE_LENGTH = 500
    }
}
