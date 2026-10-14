package com.eldering.safety.data.repository

import com.eldering.safety.core.TimeSource
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.GrantDto
import com.eldering.safety.network.GrantLevel
import com.eldering.safety.network.SettingsDto
import com.eldering.safety.network.SharingState
import com.eldering.safety.network.onSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A family member who gets my accident alert in the app. */
@Serializable
data class AlertRecipient(val userId: String, val name: String)

/**
 * Who in my family gets my accident/SOS alert in the app, for the countdown ("To Mom, Dad (app) and 1 emergency
 * contact (SMS)") and the incident's `details` (so viewers see who else was alerted).
 *
 * Mirrors the server's RBAC rule (SPEC): a co-member V sees my incidents iff grant(me→V) ∈ {live, emergency} AND (my
 * sharing is on OR always_alert_on_accident). The grants come from `GET /v1/grants` (implicit defaults included),
 * the settings from the cached `/v1/me`.
 *
 * The countdown must never wait for the network, so the last answer is kept on the device ([Cache], tiny,
 * no location) and [current] reads only that. It is refreshed while the app or the service is running
 * ([refreshIfStale]), and updated at once when the Sharing screen loads or changes grants or settings.
 */
class AlertRecipientsRepository(
    private val grants: suspend () -> ApiResult<List<GrantDto>>,
    private val settings: () -> SettingsDto?,
    private val cache: Cache,
    private val time: TimeSource = TimeSource.System,
) {
    /** Where the last answer is kept (SharedPreferences on the phone, memory in tests). Synchronous and cheap. */
    interface Cache {
        fun load(): Snapshot?
        fun save(snapshot: Snapshot)
        fun clear()

        class Memory : Cache {
            @Volatile private var value: Snapshot? = null
            override fun load(): Snapshot? = value
            override fun save(snapshot: Snapshot) {
                value = snapshot
            }

            override fun clear() {
                value = null
            }
        }
    }

    @Serializable
    data class CachedGrant(val viewerId: String, val name: String, val level: GrantLevel)

    /** The raw inputs, so a settings change can be applied without another grants request. */
    @Serializable
    data class Snapshot(
        val grants: List<CachedGrant>,
        val updatedAtMs: Long,
        /** Last known settings; used while `/v1/me` is not loaded in this process. */
        val sharingOn: Boolean? = null,
        val alwaysAlert: Boolean? = null,
    ) {
        fun encode(): String = json.encodeToString(serializer(), this)

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun decode(text: String?): Snapshot? {
                if (text.isNullOrBlank()) return null
                return try {
                    json.decodeFromString(serializer(), text)
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    @Volatile
    private var memory: Snapshot? = null

    @Volatile
    private var loaded = false

    /** The family members my alert reaches, from the last known grants and settings. Never touches the network. */
    fun current(): List<AlertRecipient> {
        val snapshot = snapshot() ?: return emptyList()
        val s = settings()
        val sharingOn = s?.let { isSharingOn(it, time.nowMillis()) } ?: snapshot.sharingOn
        val alwaysAlert = s?.alwaysAlertOnAccident ?: snapshot.alwaysAlert
        return compute(snapshot.grants, sharingOn, alwaysAlert)
    }

    /** Loads `GET /v1/grants` and stores the answer. */
    suspend fun refresh(): ApiResult<List<AlertRecipient>> {
        val result = grants()
        return when (result) {
            is ApiResult.Success -> {
                onGrantsLoaded(result.value)
                ApiResult.Success(current())
            }
            is ApiResult.Failure -> result
        }
    }

    /** [refresh] unless the stored answer is younger than [maxAgeMs]. */
    suspend fun refreshIfStale(maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
        val snapshot = snapshot()
        val now = time.nowMillis()
        if (snapshot != null && now >= snapshot.updatedAtMs && now - snapshot.updatedAtMs < maxAgeMs) {
            rememberSettings(snapshot)
            return
        }
        refresh()
    }

    /** The Sharing screen loaded my grants. */
    fun onGrantsLoaded(list: List<GrantDto>) {
        save(Snapshot(list.map { CachedGrant(it.viewerId, it.displayName, it.level) }, time.nowMillis()))
    }

    /** One grant was changed in the Sharing screen. */
    fun onGrantChanged(grant: GrantDto) {
        val snapshot = snapshot() ?: return
        val updated = snapshot.grants.map {
            if (it.viewerId == grant.viewerId) it.copy(level = grant.level, name = grant.displayName.ifBlank { it.name }) else it
        }
        save(snapshot.copy(grants = updated))
    }

    /** Settings changed (or `/v1/me` was loaded): keep the flags for the next process. */
    fun onSettingsChanged() {
        snapshot()?.let { save(it) }
    }

    /** Sign-out / account switch. */
    fun clear() {
        memory = null
        loaded = true
        cache.clear()
    }

    private fun rememberSettings(snapshot: Snapshot) {
        val s = settings() ?: return
        val on = isSharingOn(s, time.nowMillis())
        if (on != snapshot.sharingOn || s.alwaysAlertOnAccident != snapshot.alwaysAlert) save(snapshot)
    }

    private fun save(snapshot: Snapshot) {
        val s = settings()
        val withSettings = if (s == null) snapshot else snapshot.copy(sharingOn = isSharingOn(s, time.nowMillis()), alwaysAlert = s.alwaysAlertOnAccident)
        memory = withSettings
        loaded = true
        try {
            cache.save(withSettings)
        } catch (e: RuntimeException) {
            // Memory copy still works for this process.
        }
    }

    private fun snapshot(): Snapshot? {
        if (!loaded) {
            memory = try {
                cache.load()
            } catch (e: RuntimeException) {
                null
            }
            loaded = true
        }
        return memory
    }

    companion object {
        const val DEFAULT_MAX_AGE_MS = 30 * 60_000L

        private fun isSharingOn(s: SettingsDto, nowMs: Long): Boolean =
            s.effectiveSharingState == SharingState.ON ||
                (s.sharingState == SharingState.PAUSED && s.pausedUntil != null && s.pausedUntil.toEpochMilli() <= nowMs)

        /**
         * SPEC RBAC for incidents: grant live/emergency, and sharing on or always-alert. Unknown settings count as
         * the server defaults (always alert on accident = true).
         */
        fun compute(grants: List<CachedGrant>, sharingOn: Boolean?, alwaysAlert: Boolean?): List<AlertRecipient> {
            if (sharingOn != true && alwaysAlert == false) return emptyList()
            return grants
                .filter { it.level == GrantLevel.LIVE || it.level == GrantLevel.EMERGENCY }
                .distinctBy { it.viewerId }
                .map { AlertRecipient(it.viewerId, it.name.trim().ifEmpty { "?" }) }
                .sortedBy { it.name.lowercase() }
        }
    }
}

/** Keeps the family-recipient snapshot in a small private SharedPreferences file. */
class PrefsAlertRecipientsCache(context: android.content.Context) : AlertRecipientsRepository.Cache {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

    override fun load(): AlertRecipientsRepository.Snapshot? = AlertRecipientsRepository.Snapshot.decode(prefs.getString(KEY, null))

    override fun save(snapshot: AlertRecipientsRepository.Snapshot) {
        prefs.edit().putString(KEY, snapshot.encode()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val FILE = "alert_recipients"
        const val KEY = "snapshot_v1"
    }
}

/** Lets [SharingRepository] tell the recipients cache about changes without depending on it. */
internal fun ApiResult<GrantDto>.alsoUpdate(recipients: AlertRecipientsRepository?): ApiResult<GrantDto> =
    onSuccess { recipients?.onGrantChanged(it) }
