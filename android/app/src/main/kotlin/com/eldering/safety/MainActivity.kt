package com.eldering.safety

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eldering.safety.service.ProtectionStarter
import com.eldering.safety.ui.navigation.ElderingRoot
import com.eldering.safety.ui.theme.ElderingTheme
import kotlinx.coroutines.flow.MutableStateFlow

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
    }

    override fun onStart() {
        super.onStart()
        // Accident protection runs in SafetyService; (re)start it while we are in front (see ProtectionStarter).
        ProtectionStarter.onAppVisible(this)
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
