package com.eldering.safety.ui.alert

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eldering.safety.core.appGraph
import com.eldering.safety.ui.platform.ExternalIntents

/** Home → "Hold 3 s for SOS": raises the SOS and shows the alert screen in its "sent" state. */
@Composable
fun SosRoute(onClose: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val sos: SosViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SosViewModel(
                    savedState = createSavedStateHandle(),
                    trigger = graph.sosTrigger,
                    incidents = graph.incidentRepository,
                    dialEmergency = { ExternalIntents.dialEmergency(graph.appContext) },
                )
            }
        },
    )
    val alert: AccidentAlertViewModel = viewModel(
        key = "sos-alert",
        factory = AccidentAlertViewModel.factory(
            info = sos.alertInfo(graph.time.nowMillis()),
            actions = sos,
            deliveries = sos.deliveries,
            time = graph.time,
        ),
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(sos, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { sos.pollAcks() }
    }
    AccidentAlertRoute(alert, onClose)
}
