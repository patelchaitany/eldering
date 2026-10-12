package com.eldering.safety.data.outbox

import android.util.Log
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.data.db.OutboxItem
import com.eldering.safety.data.db.OutboxType
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.ApiJson
import com.eldering.safety.network.AppendTrailRequest
import com.eldering.safety.network.CreateIncidentRequest
import com.eldering.safety.network.ElderingApi
import com.eldering.safety.network.ResolveIncidentRequest
import com.eldering.safety.network.Resolution
import com.eldering.safety.network.TrailPointDto
import com.eldering.safety.network.apiCall
import androidx.room.withTransaction
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Delivers [OutboxItem]s (incident create, live-trail appends, resolve) to the server.
 *
 * Guarantees:
 * - Per incident, rows are sent strictly in insertion order and one at a time (a per-incident lock), so an append
 *   never overtakes its create. Different incidents do not block each other.
 * - Retries are safe: creates carry the incident key as `Idempotency-Key`, the server ignores duplicate trail
 *   points (same recorded_at), and an already-closed incident (409) counts as resolved.
 * - Consecutive pending appends of one incident are merged into a single request.
 * - Transient failures (network, 408/429/5xx, malformed responses) back off exponentially; client errors fail the
 *   row permanently; rows older than [MAX_ITEM_AGE_MS] (the share-link lifetime) expire.
 * - Rows only ever go out under the account that created them.
 */
class OutboxProcessor(
    private val api: ElderingApi,
    private val database: ElderingDatabase,
    private val sessionStore: SessionStore,
    private val time: TimeSource = TimeSource.System,
    private val json: Json = ApiJson,
) {
    data class DrainResult(
        /** Pending rows of the signed-in user after this pass (all retryable). */
        val remaining: Int,
        val signedOut: Boolean = false,
        val lastError: ApiError? = null,
    )

    private val outboxDao = database.outboxDao()
    private val ownDao = database.ownIncidentDao()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(incidentKey: String): Mutex = locks.getOrPut(incidentKey) { Mutex() }

    /**
     * Sends what is due. [ignoreBackoff] is for WorkManager runs (which have their own backoff) and for the first
     * attempt of a brand-new incident; opportunistic calls respect each row's next-attempt time.
     * [onlyIncident] restricts the pass to one incident (used inline right after an emergency is raised, so it
     * never waits behind older backlog).
     */
    suspend fun drain(ignoreBackoff: Boolean = false, onlyIncident: String? = null): DrainResult {
        val session = sessionStore.current() ?: return DrainResult(remaining = 0, signedOut = true)
        val userId = session.userId
        val keys = onlyIncident?.let(::listOf)
            ?: outboxDao.pendingForUser(userId).map { it.incidentKey }.distinct()
        var lastError: ApiError? = null
        for (key in keys) {
            when (val step = drainIncident(key, userId, ignoreBackoff)) {
                Step.Done, Step.Waiting -> Unit
                is Step.Retry -> lastError = step.error
                is Step.Rejected -> lastError = step.error
                is Step.Abort -> {
                    lastError = step.error
                    break // offline or signed out: the other incidents would fail the same way
                }
            }
        }
        return DrainResult(
            remaining = outboxDao.pendingCount(userId),
            signedOut = lastError is ApiError.SignedOut,
            lastError = lastError,
        )
    }

    /**
     * For "I'm OK" on an incident that never left the phone: drops its queued rows and marks it cancelled, so
     * nobody is alerted about a false alarm after the fact. Returns false if the create may already have reached
     * the server (it was attempted at least once) — then it must be resolved on the server instead.
     */
    suspend fun cancelIfNeverSent(incidentKey: String, resolution: Resolution): Boolean {
        lockFor(incidentKey).withLock {
            val pending = outboxDao.pendingForIncident(incidentKey)
            val create = pending.firstOrNull { it.type == OutboxType.CREATE_INCIDENT } ?: return false
            if (create.attempts > 0) return false
            database.withTransaction {
                outboxDao.deleteForIncident(incidentKey)
                ownDao.setStatus(incidentKey, OwnIncidentStatus.CANCELLED, resolution.wireName(), time.nowMillis())
            }
            return true
        }
    }

    private sealed interface Step {
        data object Done : Step
        data object Waiting : Step
        data class Retry(val error: ApiError) : Step
        /** The incident's create was refused for good; nothing else of it can be sent. */
        data class Rejected(val error: ApiError?) : Step
        data class Abort(val error: ApiError) : Step
    }

    private sealed interface Sent {
        data object Ok : Sent
        data class Failed(val error: ApiError) : Sent
        data class Rejected(val reason: String) : Sent
    }

    private suspend fun drainIncident(key: String, userId: String, ignoreBackoff: Boolean): Step =
        lockFor(key).withLock { drainIncidentLocked(key, userId, ignoreBackoff) }

    /** Must hold the incident's lock. Sends rows head-first until the incident is empty, waiting or failing. */
    private suspend fun drainIncidentLocked(key: String, userId: String, ignoreBackoff: Boolean): Step {
        while (true) {
            val pending = outboxDao.pendingForIncident(key)
            val head = pending.firstOrNull() ?: return Step.Done
            if (head.userId != userId) return Step.Done
            val now = time.nowMillis()
            if (now - head.createdAtMs > MAX_ITEM_AGE_MS) {
                expire(key, now)
                return Step.Done
            }
            if (!ignoreBackoff && head.nextAttemptAtMs > now) return Step.Waiting

            val batch = if (head.type == OutboxType.APPEND_TRAIL) appendBatch(pending) else listOf(head)
            when (val sent = send(key, head, batch)) {
                Sent.Ok -> outboxDao.delete(batch.map { it.id })
                is Sent.Rejected -> {
                    rejectPermanently(key, head, batch, sent.reason, now)
                    if (head.type == OutboxType.CREATE_INCIDENT) return Step.Rejected(null)
                }
                is Sent.Failed -> {
                    val error = sent.error
                    when {
                        error is ApiError.SignedOut -> return Step.Abort(error)
                        // 401 even after a token refresh: an auth problem, not a problem with this row. Keep it.
                        error is ApiError.Http && error.isUnauthorized -> return Step.Abort(error)
                        error.isRetryable || error is ApiError.Unexpected -> {
                            // Unexpected = unreadable 2xx: the server may have applied it; an idempotent retry is safe.
                            val retryAfter = (error as? ApiError.Http)?.retryAfter
                            outboxDao.recordFailure(head.id, now + backoffMs(head.attempts + 1, retryAfter), describe(error))
                            return if (error is ApiError.Network) Step.Abort(error) else Step.Retry(error)
                        }
                        else -> {
                            handleClientError(key, head, batch, error as ApiError.Http, now)
                            if (head.type == OutboxType.CREATE_INCIDENT) return Step.Rejected(error)
                        }
                    }
                }
            }
        }
    }

    /** Leading pending appends, merged up to [MAX_POINTS_PER_APPEND] points (at least one row). */
    private fun appendBatch(pending: List<OutboxItem>): List<OutboxItem> {
        val batch = mutableListOf<OutboxItem>()
        var points = 0
        for (row in pending) {
            if (row.type != OutboxType.APPEND_TRAIL) break
            val n = decodeOrNull(AppendTrailRequest.serializer(), row.payloadJson)?.points?.size ?: 0
            if (batch.isNotEmpty() && points + n > MAX_POINTS_PER_APPEND) break
            batch += row
            points += n
        }
        return batch
    }

    private suspend fun send(key: String, head: OutboxItem, batch: List<OutboxItem>): Sent = when (head.type) {
        OutboxType.CREATE_INCIDENT -> {
            val request = decodeOrNull(CreateIncidentRequest.serializer(), head.payloadJson)
            if (request == null) {
                Sent.Rejected("Unreadable create payload")
            } else {
                apiCall { api.createIncident(idempotencyKey = key, body = request) }.toSent { response ->
                    ownDao.markCreated(
                        key = key,
                        serverId = response.id,
                        shareUrl = response.shareUrl,
                        shareExpiresAtMs = response.shareExpiresAt.toEpochMilli(),
                        nowMs = time.nowMillis(),
                    )
                }
            }
        }
        OutboxType.APPEND_TRAIL -> {
            val serverId = ownDao.get(key)?.serverId
            val points = batch.flatMap { decodeOrNull(AppendTrailRequest.serializer(), it.payloadJson)?.points.orEmpty() }
                .distinctBy(TrailPointDto::recordedAt)
                .sortedBy(TrailPointDto::recordedAt)
            when {
                serverId == null -> Sent.Rejected("Incident was never created on the server")
                points.isEmpty() -> Sent.Ok
                else -> apiCall { api.appendIncidentTrail(serverId, AppendTrailRequest(points)) }.toSent { }
            }
        }
        OutboxType.RESOLVE_INCIDENT -> {
            val serverId = ownDao.get(key)?.serverId
            val request = decodeOrNull(ResolveIncidentRequest.serializer(), head.payloadJson)
            when {
                serverId == null -> Sent.Rejected("Incident was never created on the server")
                request == null -> Sent.Rejected("Unreadable resolve payload")
                else -> apiCall { api.resolveIncident(serverId, request) }.toSent { summary ->
                    ownDao.setStatus(key, OwnIncidentStatus.RESOLVED, (summary.resolution ?: request.resolution).wireName(), time.nowMillis())
                }
            }
        }
    }

    private suspend fun handleClientError(key: String, head: OutboxItem, batch: List<OutboxItem>, error: ApiError.Http, now: Long) {
        val closedOrGone = error.isConflict || error.isNotFound
        when {
            // The incident was resolved elsewhere (a family member marked it handled) or expired on the server:
            // later appends are pointless and a resolve has nothing left to do.
            head.type == OutboxType.APPEND_TRAIL && closedOrGone -> outboxDao.delete(
                outboxDao.pendingOfType(key, OutboxType.APPEND_TRAIL).map { it.id },
            )
            head.type == OutboxType.RESOLVE_INCIDENT && closedOrGone -> {
                outboxDao.delete(listOf(head.id))
                val resolution = decodeOrNull(ResolveIncidentRequest.serializer(), head.payloadJson)?.resolution
                ownDao.setStatus(key, OwnIncidentStatus.RESOLVED, resolution?.wireName(), now)
            }
            else -> rejectPermanently(key, head, batch, describe(error), now)
        }
    }

    private suspend fun rejectPermanently(key: String, head: OutboxItem, batch: List<OutboxItem>, reason: String, now: Long) {
        Log.w(TAG, "Outbox ${head.type} rejected permanently: $reason")
        if (head.type == OutboxType.CREATE_INCIDENT) {
            // Nothing after a failed create can succeed.
            database.withTransaction {
                outboxDao.failAllForIncident(key, reason)
                ownDao.setStatus(key, OwnIncidentStatus.FAILED, null, now)
            }
        } else {
            batch.forEach { outboxDao.markFailed(it.id, reason) }
        }
    }

    private suspend fun expire(key: String, now: Long) {
        database.withTransaction {
            outboxDao.failAllForIncident(key, "expired")
            val incident = ownDao.get(key)
            if (incident?.status == OwnIncidentStatus.QUEUED) {
                ownDao.setStatus(key, OwnIncidentStatus.FAILED, null, now)
            }
        }
    }

    private inline fun <T> ApiResult<T>.toSent(onOk: (T) -> Unit): Sent = when (this) {
        is ApiResult.Success -> {
            onOk(value)
            Sent.Ok
        }
        is ApiResult.Failure -> Sent.Failed(error)
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, payload: String): T? = try {
        json.decodeFromString(serializer, payload)
    } catch (e: SerializationException) {
        Log.e(TAG, "Corrupt outbox payload", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Corrupt outbox payload", e)
        null
    }

    companion object {
        private const val TAG = "OutboxProcessor"

        /** Same as the server's share-link lifetime: after 72 h an alert is no longer useful. */
        const val MAX_ITEM_AGE_MS: Long = 72L * 60 * 60 * 1000
        const val BASE_BACKOFF_MS: Long = 15_000
        const val MAX_BACKOFF_MS: Long = 15 * 60_000
        const val MAX_POINTS_PER_APPEND: Int = 1_000

        fun backoffMs(attempt: Int, retryAfter: Duration? = null): Long {
            val exponential = BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 16)
            return maxOf(exponential.coerceAtMost(MAX_BACKOFF_MS), retryAfter?.toMillis() ?: 0L)
        }

        private fun describe(error: ApiError): String = (error.message ?: error.javaClass.simpleName).take(500)
    }
}

/** Wire name, e.g. `user_ok`. */
internal fun Resolution.wireName(): String =
    (ApiJson.encodeToJsonElement(Resolution.serializer(), this) as JsonPrimitive).content
