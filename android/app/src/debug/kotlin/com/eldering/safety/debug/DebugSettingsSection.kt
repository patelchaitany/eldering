package com.eldering.safety.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.core.appGraph
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DesignCard
import com.eldering.safety.ui.components.DesignSwitch
import com.eldering.safety.ui.theme.Eldering
import kotlinx.coroutines.launch

/**
 * Debug builds only (this file is in src/debug; src/release has an empty stand-in): the "Allow real SMS" switch.
 * Default OFF, so debug builds only log accident/SOS texts (dry run). Turning it on asks for confirmation.
 */
@Composable
fun DebugSettingsSection(modifier: Modifier = Modifier) {
    val preferences = LocalContext.current.appGraph.appPreferences
    val allowRealSms by preferences.allowRealSmsInDebug.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf(false) }
    DesignCard(modifier = modifier) {
        Text(
            stringResource(R.string.debug_section_title),
            style = Eldering.type.cardTitle,
            color = Eldering.palette.ink,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.debug_allow_sms), style = Eldering.type.name, color = Eldering.palette.ink)
                Text(
                    stringResource(if (allowRealSms) R.string.debug_allow_sms_on else R.string.debug_allow_sms_off),
                    style = Eldering.type.caption,
                    color = if (allowRealSms) Eldering.palette.alert else Eldering.palette.inkMuted,
                )
            }
            DesignSwitch(
                checked = allowRealSms,
                onCheckedChange = { enable ->
                    if (enable) confirm = true else scope.launch { preferences.setAllowRealSmsInDebug(false) }
                },
                contentDescription = stringResource(R.string.debug_allow_sms),
            )
        }
        val context = LocalContext.current
        DesignButton(
            text = stringResource(R.string.debug_open_gallery),
            onClick = { context.startActivity(UiGalleryActivity.intent(context)) },
            colors = DesignButtons.outline(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.debug_open_gallery_hint), style = Eldering.type.caption, color = Eldering.palette.inkMuted)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.debug_allow_sms_confirm_title)) },
            text = { Text(stringResource(R.string.debug_allow_sms_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { preferences.setAllowRealSmsInDebug(true) }
                }) { Text(stringResource(R.string.debug_allow_sms_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
