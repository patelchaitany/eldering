package com.eldering.safety

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eldering.safety.alert.AccidentAlertActivity
import com.eldering.safety.alert.AlertAutoOpen
import com.eldering.safety.core.appGraph
import com.eldering.safety.core.catchingNonCancellation
import com.eldering.safety.service.ProtectionStarter
import com.eldering.safety.ui.map.MapWarmUp
import com.eldering.safety.ui.navigation.ElderingRoot
import com.eldering.safety.ui.theme.ElderingTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The app's single activity: onboarding, family map and every screen except the accident countdown. */
class MainActivity : ComponentActivity() {

    private val pendingIncident = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ElderingTheme {
                ElderingRoot(pendingIncident = pendingIncident, onIncidentConsumed = { pendingIncident.value = null })
            }
        }
        // Accident protection runs in SafetyService; (re)start it while we are in front and someone is signed in
        // (see ProtectionStarter). Re-runs on every start and right after a sign-in.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appGraph.sessionStore.session.map { it != null }.distinctUntilChanged().collect { signedIn ->
                    if (signedIn) ProtectionStarter.onAppVisible(this@MainActivity)
                }
            }
        }
        // Cold-start ANR traces showed HomeViewModel.<init> and FamilyMapCard composition on the main thread: build
        // the HTTP stack (OkHttp/TLS, Retrofit), Room and MapLibre's native library in the background instead, so
        // the first screen only finds them ready.
        MapWarmUp.start(applicationContext)
        val graph = appGraph
        graph.applicationScope.launch {
            catchingNonCancellation {
                graph.authRepository
                graph.incidentRepository
                graph.contactsRepository
                graph.database.openHelper.writableDatabase
            }
        }
    }

    override fun onStart() {
        super.onStart()
        showRunningAlert()
    }

    /**
     * A countdown (or a send) is running but its screen is not in front, e.g. notifications are off so the
     * full-screen intent never showed it: the user heard the alarm and opened the app, so show "I'm OK" now.
     */
    private fun showRunningAlert() {
        val state = appGraph.alertController.state.value
        if (!AlertAutoOpen.shouldOpen(state)) return
        try {
            startActivity(AccidentAlertActivity.intent(this))
        } catch (e: RuntimeException) {
            Log.w("MainActivity", "Could not open the alert screen: ${e.javaClass.simpleName}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_INCIDENT_ID)?.takeIf { UUID_PATTERN.matches(it) }?.let { pendingIncident.value = it }
    }

    companion object {
        /** Incident id (UUID) of a family member's accident to open ("What family sees"). */
        const val EXTRA_INCIDENT_ID = "com.eldering.safety.extra.INCIDENT_ID"

        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** For family-alert notifications: opens the alert screen for [incidentId] (after sign-in checks). */
        fun familyAlertIntent(context: Context, incidentId: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_INCIDENT_ID, incidentId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
