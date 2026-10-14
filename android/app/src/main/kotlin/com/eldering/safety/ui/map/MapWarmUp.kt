package com.eldering.safety.ui.map

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.LibraryLoader

/**
 * Loads MapLibre's native library (~12 MB per ABI) on a background thread. Loading it lazily from the first
 * `MapLibre.getInstance()` / `MapView` call put that work on the main thread while Home composed its map, which
 * showed up in cold-start ANR traces (`FamilyMapCard` composition). [MapLibreMap] waits for [ready] before it
 * creates its MapView, showing the plain ground colour for that moment.
 *
 * Never started by `ElderingApp` (the SafetyService shares the process and never needs a map).
 */
object MapWarmUp {
    private const val TAG = "MapWarmUp"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loaded = CompletableDeferred<Boolean>()

    @Volatile
    private var started = false

    /** Null while loading; then whether the native library loaded (false: show the schematic map). */
    @Volatile
    var result: Boolean? = null
        private set

    /** Starts loading if nobody did yet. Cheap and idempotent; call it as early as an activity with a map starts. */
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            val ok = try {
                LibraryLoader.load()
                true
            } catch (e: Throwable) { // UnsatisfiedLinkError is an Error
                Log.w(TAG, "MapLibre native library unavailable", e)
                false
            }
            result = ok
            loaded.complete(ok)
        }
    }

    /** Waits for the library (starting the load if needed). */
    suspend fun ready(context: Context): Boolean {
        start(context)
        return loaded.await()
    }
}
