package com.eldering.safety.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Small, non-secret device preferences (Preferences DataStore `app_prefs`). Not backed up (see backup rules).
 *
 * @param isDebugBuild `BuildConfig.DEBUG`; decides whether [allowRealSms] can ever be false.
 */
class AppPreferences(
    private val dataStore: DataStore<Preferences>,
    private val isDebugBuild: Boolean,
) {
    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e
    }

    /** The permission walkthrough was finished (or skipped through) once; afterwards Home is the start screen. */
    val permissionsWalkthroughDone: Flow<Boolean> = data.map { it[PERMISSIONS_DONE] ?: false }.distinctUntilChanged()

    /** The user confirmed the OnePlus/OPPO background-activity steps (cannot be detected programmatically). */
    val oemBackgroundGuideDone: Flow<Boolean> = data.map { it[OEM_GUIDE_DONE] ?: false }.distinctUntilChanged()

    /**
     * Debug builds only: the "Allow real SMS" switch. Default OFF, so a debug build never texts anyone unless a
     * developer turns this on in Settings (SPEC "Test safety").
     */
    val allowRealSmsInDebug: Flow<Boolean> = data.map { it[ALLOW_REAL_SMS] ?: false }.distinctUntilChanged()

    /**
     * Whether accident/SOS SMS may really be sent: always in release builds; in debug builds only when the
     * "Allow real SMS" switch is on. The SMS sender must check this and dry-run (log only) otherwise.
     */
    val allowRealSms: Flow<Boolean> = allowRealSmsInDebug.map { !isDebugBuild || it }.distinctUntilChanged()

    suspend fun realSmsAllowed(): Boolean = allowRealSms.first()

    suspend fun setPermissionsWalkthroughDone(done: Boolean) {
        dataStore.edit { it[PERMISSIONS_DONE] = done }
    }

    suspend fun setOemBackgroundGuideDone(done: Boolean) {
        dataStore.edit { it[OEM_GUIDE_DONE] = done }
    }

    /** Ignored in release builds (real SMS is always on there). */
    suspend fun setAllowRealSmsInDebug(allowed: Boolean) {
        if (!isDebugBuild) return
        dataStore.edit { it[ALLOW_REAL_SMS] = allowed }
    }

    companion object {
        const val FILE_NAME = "app_prefs"
        private val PERMISSIONS_DONE = booleanPreferencesKey("permissions_walkthrough_done")
        private val OEM_GUIDE_DONE = booleanPreferencesKey("oem_background_guide_done")
        private val ALLOW_REAL_SMS = booleanPreferencesKey("debug_allow_real_sms")
    }
}
