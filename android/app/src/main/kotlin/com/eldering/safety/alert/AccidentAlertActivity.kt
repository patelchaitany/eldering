package com.eldering.safety.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.eldering.safety.MainActivity
import com.eldering.safety.core.appGraph
import com.eldering.safety.ui.alert.AccidentAlertRoute
import com.eldering.safety.ui.alert.AccidentAlertViewModel
import com.eldering.safety.ui.theme.ElderingTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * The accident countdown over the lock screen (full-screen-intent target; `showWhenLocked`, `turnScreenOn`, never
 * dismisses the keyguard: "I'm OK" must work without unlocking).
 *
 * It only shows and signals: the countdown is owned by [AlertController] (timed in the app process with a
 * wakelock), so closing or rotating this screen never stops an alert.
 */
class AccidentAlertActivity : ComponentActivity() {

    private var boundAlertId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        val graph = appGraph
        val controller = graph.alertController
        val state = controller.state.value
        val alert = state.alert
        if (alert == null || (state is AlertState.Finished && savedInstanceState == null)) {
            // Nothing to show (stale notification, or the alert ended before this screen opened).
            finish()
            return
        }
        boundAlertId = alert.id
        // Keyed by alert: ViewModels survive recreate(), and a reused screen must not show the previous alert.
        val viewModel = ViewModelProvider(
            this,
            AccidentAlertViewModel.factory(
                info = AlertPresentation.info(alert, state),
                actions = controller,
                feedbackSink = controller,
                deliveries = controller.state.map(AlertPresentation::deliveries),
                externalPhase = controller.state.mapNotNull { AlertPresentation.phase(it, alert.id) },
                time = graph.time,
            ),
        )["alert-${alert.id}", AccidentAlertViewModel::class.java]
        setContent {
            ElderingTheme {
                AccidentAlertRoute(viewModel, onClose = ::close)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A newer alert than the one on screen: rebuild for it.
        val current = appGraph.alertController.state.value.alert?.id
        if (current != null && current != boundAlertId) recreate()
    }

    private fun close() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AccidentAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** "I'm OK" / "Send help now" / "I'm safe now" from the alert notification (not exported). */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = context.appGraph.alertController
        when (intent.action) {
            ACTION_IM_OK -> controller.imOk()
            ACTION_SEND_NOW -> controller.sendNow()
        }
    }

    companion object {
        const val ACTION_IM_OK = "com.eldering.safety.alert.IM_OK"
        const val ACTION_SEND_NOW = "com.eldering.safety.alert.SEND_NOW"
    }
}
