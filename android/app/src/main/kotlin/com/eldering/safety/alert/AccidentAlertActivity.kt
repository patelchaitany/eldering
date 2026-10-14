package com.eldering.safety.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eldering.safety.MainActivity
import com.eldering.safety.core.appGraph
import com.eldering.safety.core.catchingNonCancellation
import com.eldering.safety.network.AckDto
import com.eldering.safety.network.ApiResult
import com.eldering.safety.ui.alert.AccidentAlertRoute
import com.eldering.safety.ui.alert.AccidentAlertViewModel
import com.eldering.safety.ui.theme.ElderingTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * The accident countdown over the lock screen (full-screen-intent target; `showWhenLocked`, `turnScreenOn`, never
 * dismisses the keyguard: "I'm OK" must work without unlocking).
 *
 * It only shows and signals: the countdown is owned by [AlertController] (timed in the app process with a
 * wakelock), so closing or rotating this screen never stops an alert. The display is kept on by the screen itself
 * while counting (and briefly once sent), not for the whole live hour; the screen closes itself a minute after the
 * alert ended or was cancelled.
 */
class AccidentAlertActivity : ComponentActivity() {

    private var boundAlertId: Long? = null

    /** Family acknowledgements of the sent alert ("Seen" / "On the way"), polled while this screen is visible. */
    private val acks = MutableStateFlow<List<AckDto>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()

        val graph = appGraph
        val controller = graph.alertController
        val state = controller.state.value
        val alert = state.alert
        if (alert == null || (state is AlertState.Finished && savedInstanceState == null)) {
            // Nothing to show (stale notification from a previous process, or the alert ended before this screen
            // opened): make sure that notification goes away too.
            if (state is AlertState.Idle || state is AlertState.Finished) AlertNotifications.cancel(this)
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
                deliveries = combine(controller.state, acks) { s, a -> AlertPresentation.deliveries(s, a) },
                externalPhase = controller.state.mapNotNull { AlertPresentation.phase(it, alert.id) },
                time = graph.time,
            ),
        )["alert-${alert.id}", AccidentAlertViewModel::class.java]
        setContent {
            ElderingTheme {
                AccidentAlertRoute(viewModel, onClose = ::close, onAutoClose = ::finish)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { pollAcks(alert.id) }
        }
        lifecycleScope.launch {
            // Live sharing ended by itself (closed elsewhere or the hour is over): nothing left to do here.
            controller.state.first { it is AlertState.Finished && it.ending == Ending.ENDED && it.alert.id == alert.id }
            delay(AUTO_CLOSE_AFTER_END_MS)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A newer alert than the one on screen: rebuild for it.
        val current = appGraph.alertController.state.value.alert?.id
        if (current != null && current != boundAlertId) recreate()
    }

    /** Only while visible and only once the alert is out: the owner's incident detail lists every ack. */
    private suspend fun pollAcks(alertId: Long) {
        val graph = appGraph
        while (true) {
            val s = graph.alertController.state.value
            val key = (s as? AlertState.Sent)?.takeIf { it.alert.id == alertId }?.result?.incidentKey
            if (key != null) {
                val serverId = catchingNonCancellation { graph.incidentRepository.ownIncident(key)?.serverId }
                if (serverId != null) {
                    (graph.incidentRepository.detail(serverId) as? ApiResult.Success)?.value?.acks?.let { acks.value = it }
                }
            }
            delay(if (key == null) ACK_WAIT_MS else ACK_POLL_MS)
        }
    }

    private fun close() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        private const val AUTO_CLOSE_AFTER_END_MS = 60_000L
        private const val ACK_POLL_MS = 15_000L
        private const val ACK_WAIT_MS = 2_000L

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
        // An action on a notification whose alert no longer exists (e.g. left behind by a killed process) must
        // not leave that ongoing notification up forever.
        val now = controller.state.value
        if (now is AlertState.Idle || now is AlertState.Finished) AlertNotifications.cancel(context)
    }

    companion object {
        const val ACTION_IM_OK = "com.eldering.safety.alert.IM_OK"
        const val ACTION_SEND_NOW = "com.eldering.safety.alert.SEND_NOW"
    }
}
