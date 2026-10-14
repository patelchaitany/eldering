package com.eldering.safety.data.repository

import androidx.room.withTransaction
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.data.db.OutboxItem
import com.eldering.safety.data.db.OutboxType
import com.eldering.safety.data.db.OwnIncident
import com.eldering.safety.data.db.OwnIncidentStatus
import com.eldering.safety.data.outbox.OutboxProcessor
import com.eldering.safety.data.outbox.OutboxScheduler
import com.eldering.safety.data.outbox.wireName
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.network.AckDto
import com.eldering.safety.network.AckIncidentRequest
import com.eldering.safety.network.AckState
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiJson
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.AppendTrailRequest
import com.eldering.safety.network.CreateIncidentRequest
import com.eldering.safety.network.ElderingApi
import com.eldering.safety.network.IncidentDetailDto
import com.eldering.safety.network.IncidentKind
import com.eldering.safety.network.IncidentSummaryDto
import com.eldering.safety.network.ResolveIncidentRequest
import com.eldering.safety.network.Resolution
import com.eldering.safety.network.TrailPointDto
import com.eldering.safety.network.apiCall
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Incidents, from both sides.
 *
 * **My incidents** (accident or SOS raised on this phone) go through the outbox: [report] stores the incident and
 * its create request in one transaction, then tries to send it right away; if that fails WorkManager keeps
 * retrying, so an alert survives no signal, a dead server and process death. Live trail updates ([appendTrail])
 * and the resolution ([resolveOwn]) are queued behind the create and delivered in order.
 *
 * **Family incidents** (I am a viewer): [active], [detail], [acknowledge], [markHandled] are plain API calls.
 *
 * SMS to emergency contacts is not this class's job (see `alert.IncidentSender`); it must not wait for the
 * server either.
 */
class IncidentRepository(
    private val api: ElderingApi,
    private val database: ElderingDatabase,
    private val sessionStore: SessionStore,
    private val outbox: OutboxProcessor,
    private val scheduler: OutboxScheduler,
    private val time: TimeSource = TimeSource.System,
    private val json: Json = ApiJson,
    private val newIncidentKey: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface ReportResult {
        /** The server has it: family is being alerted and [shareUrl] works (for the SMS). */
        data class Sent(
            val incidentKey: String,
            val serverId: String,
            val shareUrl: String,
            val shareExpiresAtMs: Long,
        ) : ReportResult {
            override fun toString(): String = "Sent(incidentKey=$incidentKey, serverId=$serverId, shareUrl=██)"
        }

        /** Stored on the phone and queued for retry; [error] says why the first attempt failed. */
        data class Queued(val incidentKey: String, val error: ApiError?) : ReportResult

        /** The server permanently refused the upload (a client error, see [error]); it will not be retried. */
        data class Rejected(val incidentKey: String, val error: ApiError?) : ReportResult

        /** No account on this phone: nothing can be uploaded (SMS with coordinates is the only channel). */
        data object NotSignedIn : ReportResult
    }

    private val ownDao = database.ownIncidentDao()
    private val outboxDao = database.outboxDao()

    // --- My incidents -----------------------------------------------------------------------------------------

    /**
     * Stores [draft] as my incident (outbox row + local copy) and tries to upload it right away.
     *
     * [incidentKey] identifies the alert across process restarts: reporting the same key again (an interrupted send
     * resumed after a restart) never creates a second incident, it just drives the stored one forward.
     *
     * Background delivery is scheduled **before** the inline attempt, so a process death during it (OEM killer, low
     * memory, shutdown) cannot orphan the stored row; the per-incident lock serializes the two and creates are
     * idempotent on the server.
     */
    suspend fun report(draft: IncidentDraft, incidentKey: String? = null): ReportResult {
        val session = sessionStore.current() ?: return ReportResult.NotSignedIn
        val key = incidentKey ?: newIncidentKey()
        val now = time.nowMillis()
        if (ownDao.get(key) == null) {
            val request = CreateIncidentRequest(
                kind = draft.kind,
                detectedAt = Instant.ofEpochMilli(draft.detectedAtMs),
                lat = draft.lat,
                lng = draft.lng,
                details = draft.details,
                trail = draft.normalizedTrail(),
            )
            database.withTransaction {
                ownDao.insert(
                    OwnIncident(
                        incidentKey = key,
                        userId = session.userId,
                        kind = draft.kind.wireName(),
                        detectedAtMs = draft.detectedAtMs,
                        lat = draft.lat,
                        lng = draft.lng,
                        createdAtMs = now,
                        updatedAtMs = now,
                    ),
                )
                outboxDao.insert(
                    OutboxItem(
                        type = OutboxType.CREATE_INCIDENT,
                        incidentKey = key,
                        userId = session.userId,
                        payloadJson = json.encodeToString(CreateIncidentRequest.serializer(), request),
                        createdAtMs = now,
                        nextAttemptAtMs = now,
                    ),
                )
            }
        }
        scheduler.schedule()
        val drain = outbox.drain(ignoreBackoff = true, onlyIncident = key)
        val incident = ownDao.get(key)
        val serverId = incident?.serverId
        val shareUrl = incident?.shareUrl
        val shareExpiresAtMs = incident?.shareExpiresAtMs
        if (serverId != null && shareUrl != null && shareExpiresAtMs != null) {
            return ReportResult.Sent(key, serverId, shareUrl, shareExpiresAtMs)
        }
        if (incident?.status == OwnIncidentStatus.FAILED) return ReportResult.Rejected(key, drain.lastError)
        return ReportResult.Queued(key, drain.lastError)
    }

    /**
     * Queues live trail points for my incident (SPEC: every 15 s for 60 min) and tries to send them now.
     * Returns false if the incident is unknown or already closed (stop sending updates).
     */
    suspend fun appendTrail(incidentKey: String, points: List<TrailPointDto>): Boolean {
        val incident = ownDao.get(incidentKey) ?: return false
        if (incident.status != OwnIncidentStatus.QUEUED && incident.status != OwnIncidentStatus.ACTIVE) return false
        if (points.isEmpty()) return true
        val now = time.nowMillis()
        database.withTransaction {
            points.chunked(OutboxProcessor.MAX_POINTS_PER_APPEND).forEach { chunk ->
                outboxDao.insert(
                    OutboxItem(
                        type = OutboxType.APPEND_TRAIL,
                        incidentKey = incidentKey,
                        userId = incident.userId,
                        payloadJson = json.encodeToString(AppendTrailRequest.serializer(), AppendTrailRequest(chunk)),
                        createdAtMs = now,
                        nextAttemptAtMs = now,
                    ),
                )
            }
        }
        deliverSoon(incidentKey)
        return true
    }

    /**
     * Resolves my incident ("I'm OK" → [Resolution.USER_OK], wrong detection → [Resolution.FALSE_ALARM], or
     * [Resolution.HANDLED]). If the incident never left the phone it is simply cancelled; otherwise the resolve is
     * queued behind the create so family members always see it end.
     */
    suspend fun resolveOwn(incidentKey: String, resolution: Resolution): OwnIncident? {
        val incident = ownDao.get(incidentKey) ?: return null
        if (incident.status != OwnIncidentStatus.QUEUED && incident.status != OwnIncidentStatus.ACTIVE) return incident
        if (!outbox.cancelIfNeverSent(incidentKey, resolution)) {
            val now = time.nowMillis()
            database.withTransaction {
                outboxDao.insert(
                    OutboxItem(
                        type = OutboxType.RESOLVE_INCIDENT,
                        incidentKey = incidentKey,
                        userId = incident.userId,
                        payloadJson = json.encodeToString(ResolveIncidentRequest.serializer(), ResolveIncidentRequest(resolution)),
                        createdAtMs = now,
                        nextAttemptAtMs = now,
                    ),
                )
                // Optimistic: locally it is over; the queued request makes it so on the server.
                ownDao.setStatus(incidentKey, OwnIncidentStatus.RESOLVED, resolution.wireName(), now)
            }
            // Before the inline attempt (see report): family must see the emergency end even if we die now.
            scheduler.schedule()
            deliverSoon(incidentKey)
        }
        return ownDao.get(incidentKey)
    }

    fun observeOwn(incidentKey: String): Flow<OwnIncident?> = ownDao.observe(incidentKey)

    /** My incidents (newest first) for the signed-in account. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOwnIncidents(): Flow<List<OwnIncident>> = sessionStore.session.flatMapLatest { session ->
        session?.let { ownDao.observeForUser(it.userId) } ?: flowOf(emptyList())
    }

    suspend fun ownIncident(incidentKey: String): OwnIncident? = ownDao.get(incidentKey)

    /** Pending uploads across incidents (for a "not delivered yet" indicator). */
    fun observePendingUploads(): Flow<Int> = outboxDao.observePendingCount()

    /**
     * Makes sure WorkManager will deliver whatever is still queued for the signed-in account (service start, boot,
     * the service's housekeeping tick). Returns true if anything was pending.
     */
    suspend fun scheduleIfPending(): Boolean {
        val session = sessionStore.current() ?: return false
        if (outboxDao.pendingCount(session.userId) == 0) return false
        scheduler.schedule()
        return true
    }

    /** Retries everything now (e.g. when connectivity returns while the app is open). */
    suspend fun retryPending(): OutboxProcessor.DrainResult {
        val result = outbox.drain(ignoreBackoff = true)
        if (result.remaining > 0 && !result.signedOut) scheduler.schedule()
        return result
    }

    // --- Family incidents (viewer) ----------------------------------------------------------------------------

    /** Incidents visible to me (mine and my family's) that are still active. */
    suspend fun active(): ApiResult<List<IncidentSummaryDto>> = apiCall { api.activeIncidents() }

    /** Detail with trail and acks; 404 when not visible (RBAC never reveals existence). */
    suspend fun detail(incidentId: String): ApiResult<IncidentDetailDto> = apiCall { api.incident(incidentId) }

    suspend fun acknowledge(incidentId: String, state: AckState): ApiResult<AckDto> =
        apiCall { api.ackIncident(incidentId, AckIncidentRequest(state)) }

    /** Viewer: help arrived; closes the incident for everyone. */
    suspend fun markHandled(incidentId: String): ApiResult<IncidentSummaryDto> =
        apiCall { api.resolveIncident(incidentId, ResolveIncidentRequest(Resolution.HANDLED)) }

    // --- Housekeeping -----------------------------------------------------------------------------------------

    /**
     * Retention (run about hourly): closed incidents and dead outbox rows go a week after they ended; incidents
     * that never got closed locally (live updates over, nobody resolved them) a week after their share link
     * expired, together with their share URL.
     */
    suspend fun prune(): Int {
        val now = time.nowMillis()
        val cutoff = now - CLOSED_RETENTION_MS
        return ownDao.pruneClosed(cutoff) +
            ownDao.pruneStale(detectedBeforeMs = now - OutboxProcessor.MAX_ITEM_AGE_MS - CLOSED_RETENTION_MS) +
            outboxDao.pruneFailed(cutoff)
    }

    /** Deletes this account's queued uploads and incident history from the phone (sign-out / account switch). */
    suspend fun discardAccountData(userId: String) {
        database.withTransaction {
            outboxDao.deleteForUser(userId)
            ownDao.deleteForUser(userId)
        }
    }

    private suspend fun deliverSoon(incidentKey: String) {
        val result = outbox.drain(onlyIncident = incidentKey)
        if (result.remaining > 0 && !result.signedOut) scheduler.schedule()
    }

    companion object {
        const val CLOSED_RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

internal fun IncidentKind.wireName(): String =
    (ApiJson.encodeToJsonElement(IncidentKind.serializer(), this) as JsonPrimitive).content
