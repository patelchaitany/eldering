package com.eldering.safety.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrailDao {
    /** @return the row id, or -1 if a point with the same `recorded_at` already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: TrailPointEntity): Long

    @Query("SELECT * FROM trail_points ORDER BY recorded_at DESC LIMIT 1")
    suspend fun latest(): TrailPointEntity?

    @Query("SELECT * FROM trail_points WHERE recorded_at BETWEEN :fromMs AND :toMs ORDER BY recorded_at")
    suspend fun between(fromMs: Long, toMs: Long): List<TrailPointEntity>

    @Query("SELECT * FROM trail_points WHERE recorded_at >= :fromMs ORDER BY recorded_at")
    fun observeSince(fromMs: Long): Flow<List<TrailPointEntity>>

    /** Retention: drops everything recorded before [cutoffMs]. */
    @Query("DELETE FROM trail_points WHERE recorded_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /** Capacity cap: keeps only the newest [keep] points. */
    @Query(
        "DELETE FROM trail_points WHERE id NOT IN " +
            "(SELECT id FROM trail_points ORDER BY recorded_at DESC LIMIT :keep)",
    )
    suspend fun trimToNewest(keep: Int): Int

    @Query("SELECT COUNT(*) FROM trail_points")
    suspend fun count(): Int

    @Query("DELETE FROM trail_points")
    suspend fun clear()
}

@Dao
interface DetectionEventDao {
    @Insert
    suspend fun insert(event: DetectionEventRecord): Long

    @Query("SELECT * FROM detection_events WHERE id = :id")
    suspend fun get(id: Long): DetectionEventRecord?

    @Query("SELECT * FROM detection_events ORDER BY triggered_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DetectionEventRecord>>

    @Query("SELECT * FROM detection_events WHERE label IS NOT NULL ORDER BY triggered_at")
    suspend fun labeled(): List<DetectionEventRecord>

    @Query("UPDATE detection_events SET outcome = :outcome, incident_key = :incidentKey WHERE id = :id")
    suspend fun setOutcome(id: Long, outcome: DetectionOutcome, incidentKey: String?): Int

    @Query(
        "UPDATE detection_events SET label = :label, label_note = :note, labeled_at = :labeledAtMs WHERE id = :id",
    )
    suspend fun setLabel(id: Long, label: FeedbackLabel?, note: String?, labeledAtMs: Long?): Int

    /** Unlabeled events expire; labeled ones are kept as tuning data until [labeledCutoffMs]. */
    @Query(
        "DELETE FROM detection_events WHERE (label IS NULL AND triggered_at < :unlabeledCutoffMs) " +
            "OR triggered_at < :labeledCutoffMs",
    )
    suspend fun prune(unlabeledCutoffMs: Long, labeledCutoffMs: Long): Int
}

@Dao
interface EmergencyContactDao {
    @Query("SELECT * FROM emergency_contacts ORDER BY position, id")
    fun observeAll(): Flow<List<EmergencyContact>>

    @Query("SELECT * FROM emergency_contacts ORDER BY position, id")
    suspend fun all(): List<EmergencyContact>

    @Query("SELECT * FROM emergency_contacts WHERE id = :id")
    suspend fun get(id: Long): EmergencyContact?

    @Query("SELECT COALESCE(MAX(position), -1) FROM emergency_contacts")
    suspend fun maxPosition(): Int

    @Insert
    suspend fun insert(contact: EmergencyContact): Long

    @Update
    suspend fun update(contact: EmergencyContact): Int

    @Query("DELETE FROM emergency_contacts WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("UPDATE emergency_contacts SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> setPosition(id, index) }
    }
}

@Dao
interface OwnIncidentDao {
    @Insert
    suspend fun insert(incident: OwnIncident)

    @Query("SELECT * FROM own_incidents WHERE incident_key = :key")
    suspend fun get(key: String): OwnIncident?

    @Query("SELECT * FROM own_incidents WHERE server_id = :serverId")
    suspend fun getByServerId(serverId: String): OwnIncident?

    @Query("SELECT * FROM own_incidents WHERE incident_key = :key")
    fun observe(key: String): Flow<OwnIncident?>

    @Query("SELECT * FROM own_incidents WHERE user_id = :userId ORDER BY detected_at DESC")
    fun observeForUser(userId: String): Flow<List<OwnIncident>>

    @Query(
        "UPDATE own_incidents SET server_id = :serverId, share_url = :shareUrl, share_expires_at = :shareExpiresAtMs, " +
            "status = CASE WHEN status = 'QUEUED' THEN 'ACTIVE' ELSE status END, updated_at = :nowMs " +
            "WHERE incident_key = :key",
    )
    suspend fun markCreated(key: String, serverId: String, shareUrl: String, shareExpiresAtMs: Long, nowMs: Long)

    @Query("UPDATE own_incidents SET status = :status, resolution = :resolution, updated_at = :nowMs WHERE incident_key = :key")
    suspend fun setStatus(key: String, status: OwnIncidentStatus, resolution: String?, nowMs: Long)

    @Query("DELETE FROM own_incidents WHERE updated_at < :cutoffMs AND status != 'QUEUED' AND status != 'ACTIVE'")
    suspend fun pruneClosed(cutoffMs: Long): Int

    @Query("DELETE FROM own_incidents WHERE user_id = :userId")
    suspend fun deleteForUser(userId: String)
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(item: OutboxItem): Long

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxItem?

    /** Pending rows of [userId] in send order. */
    @Query("SELECT * FROM outbox WHERE state = 'PENDING' AND user_id = :userId ORDER BY id")
    suspend fun pendingForUser(userId: String): List<OutboxItem>

    @Query("SELECT * FROM outbox WHERE state = 'PENDING' AND incident_key = :key AND type = :type ORDER BY id")
    suspend fun pendingOfType(key: String, type: OutboxType): List<OutboxItem>

    @Query("SELECT * FROM outbox WHERE state = 'PENDING' AND incident_key = :key ORDER BY id")
    suspend fun pendingForIncident(key: String): List<OutboxItem>

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'PENDING' AND user_id = :userId")
    suspend fun pendingCount(userId: String): Int

    @Query("UPDATE outbox SET attempts = attempts + 1, next_attempt_at = :nextAttemptAtMs, last_error = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, nextAttemptAtMs: Long, error: String)

    @Query("UPDATE outbox SET state = 'FAILED', attempts = attempts + 1, last_error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE outbox SET state = 'FAILED', last_error = :error WHERE state = 'PENDING' AND incident_key = :key")
    suspend fun failAllForIncident(key: String, error: String)

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM outbox WHERE incident_key = :key")
    suspend fun deleteForIncident(key: String)

    @Query("DELETE FROM outbox WHERE user_id = :userId")
    suspend fun deleteForUser(userId: String)

    @Query("DELETE FROM outbox WHERE state = 'FAILED' AND created_at < :cutoffMs")
    suspend fun pruneFailed(cutoffMs: Long): Int
}
