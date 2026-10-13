package com.eldering.safety.ui.permissions

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.core.appGraph
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DesignCard
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.components.StatusChip
import com.eldering.safety.ui.components.TextLinkButton
import com.eldering.safety.service.ProtectionStarter
import com.eldering.safety.ui.platform.ExternalIntents
import com.eldering.safety.ui.theme.Eldering
import kotlinx.coroutines.launch

/**
 * Permission walkthrough (onboarding step 3, and Settings → "Permissions & battery").
 *
 * During [onboarding] it goes step by step and ends on a checklist; otherwise it opens on the checklist. Each
 * runtime permission is requested only from its own step, after its explanation. Background location is
 * requested only after the prominent disclosure on its own page (Google Play policy). Statuses are re-read
 * whenever the screen resumes (the user may come back from a settings page).
 */
@Composable
fun PermissionsRoute(
    onboarding: Boolean,
    onFinished: () -> Unit,
    onBack: () -> Unit,
    /** Arm and start accident protection when the user is done (false in the debug UI gallery). */
    startProtection: Boolean = true,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val preferences = context.appGraph.appPreferences
    val scope = rememberCoroutineScope()
    val checker = remember(context) { AndroidPermissionChecker(context) }
    val steps = remember(context) {
        PermissionPlan.steps(
            sdkInt = Build.VERSION.SDK_INT,
            hasSms = AndroidPermissionChecker.hasSms(context),
            oppoFamily = ExternalIntents.isOppoFamilyDevice(),
        )
    }
    val oemDone by preferences.oemBackgroundGuideDone.collectAsStateWithLifecycle(initialValue = false)
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }
    val statuses = remember(refreshTick, oemDone) { steps.associateWith { PermissionPlan.status(it, checker, oemDone) } }

    // null = the checklist.
    var current by rememberSaveable {
        mutableStateOf(if (onboarding) steps.indexOfFirst { statuses[it] != StepStatus.DONE }.takeIf { it >= 0 } else null)
    }
    var pending by rememberSaveable { mutableStateOf<PermissionStep?>(null) }
    var blocked by rememberSaveable { mutableStateOf(setOf<PermissionStep>()) }

    fun advance() {
        val index = current ?: return
        current = (index + 1).takeIf { it < steps.size }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        refreshTick++
        val step = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val status = PermissionPlan.status(step, checker, oemDone)
        if (status == StepStatus.DONE) {
            blocked = blocked - step
            if (onboarding) advance()
        } else if (activity != null && results.isNotEmpty() &&
            results.keys.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        ) {
            // Denied with "don't ask again" (or twice): only the settings page can grant it now.
            blocked = blocked + step
        }
    }

    fun request(step: PermissionStep) {
        val permissions = PermissionPlan.runtimePermissions(step, Build.VERSION.SDK_INT)
        when {
            step in blocked -> ExternalIntents.openAppDetails(context)
            permissions.isNotEmpty() -> {
                pending = step
                launcher.launch(permissions.toTypedArray())
            }
            step == PermissionStep.FULL_SCREEN_ALERTS -> ExternalIntents.openFullScreenIntentSettings(context)
            step == PermissionStep.BATTERY -> ExternalIntents.requestIgnoreBatteryOptimizations(context)
            step == PermissionStep.NOTIFICATIONS -> ExternalIntents.openNotificationSettings(context)
            else -> Unit
        }
    }

    fun finish() {
        scope.launch {
            preferences.setPermissionsWalkthroughDone(true)
            if (startProtection) ProtectionStarter.arm(context)
            onFinished()
        }
    }

    val index = current
    BackHandler(enabled = index != null && !onboarding) { current = null }
    if (index == null) {
        PermissionChecklist(
            steps = steps,
            statuses = statuses,
            onboarding = onboarding,
            onOpenStep = { current = steps.indexOf(it) },
            onDone = {
                if (onboarding) {
                    finish()
                } else {
                    // Picks up permissions granted here (e.g. location is now allowed).
                    if (startProtection) ProtectionStarter.arm(context)
                    onBack()
                }
            },
            onBack = if (onboarding) null else onBack,
        )
    } else {
        val step = steps[index]
        PermissionStepPage(
            step = step,
            index = index,
            total = steps.size,
            status = statuses.getValue(step),
            blocked = step in blocked,
            onPrimary = { request(step) },
            onNext = { if (onboarding) advance() else current = null },
            onOemAppInfo = { ExternalIntents.openAppDetails(context) },
            onOemAutoLaunch = { ExternalIntents.openOemAutoLaunchSettings(context) },
            onOemDone = {
                scope.launch { preferences.setOemBackgroundGuideDone(true) }
                if (onboarding) advance() else current = null
            },
            onBack = if (onboarding) null else ({ current = null }),
            onSkipAll = if (onboarding) ({ current = null }) else null,
        )
    }
}

private data class StepCopy(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val action: Int,
)

private fun copyFor(step: PermissionStep): StepCopy = when (step) {
    PermissionStep.LOCATION -> StepCopy(ElderingIcons.MapPin, R.string.perm_location_title, R.string.perm_location_body, R.string.perm_location_action)
    PermissionStep.BACKGROUND_LOCATION -> StepCopy(ElderingIcons.MapPin, R.string.perm_background_title, R.string.perm_background_body, R.string.perm_background_action)
    PermissionStep.ACTIVITY_RECOGNITION -> StepCopy(ElderingIcons.Activity, R.string.perm_activity_title, R.string.perm_activity_body, R.string.perm_allow)
    PermissionStep.NOTIFICATIONS -> StepCopy(ElderingIcons.Bell, R.string.perm_notifications_title, R.string.perm_notifications_body, R.string.perm_allow)
    PermissionStep.SMS -> StepCopy(ElderingIcons.Message, R.string.perm_sms_title, R.string.perm_sms_body, R.string.perm_allow)
    PermissionStep.FULL_SCREEN_ALERTS -> StepCopy(ElderingIcons.Warning, R.string.perm_fullscreen_title, R.string.perm_fullscreen_body, R.string.perm_open_settings)
    PermissionStep.BATTERY -> StepCopy(ElderingIcons.Battery, R.string.perm_battery_title, R.string.perm_battery_body, R.string.perm_allow)
    PermissionStep.OEM_BACKGROUND -> StepCopy(ElderingIcons.Bolt, R.string.perm_oem_title, R.string.perm_oem_body, R.string.perm_oem_done)
}

@Composable
private fun PermissionStepPage(
    step: PermissionStep,
    index: Int,
    total: Int,
    status: StepStatus,
    blocked: Boolean,
    onPrimary: () -> Unit,
    onNext: () -> Unit,
    onOemAppInfo: () -> Unit,
    onOemAutoLaunch: () -> Unit,
    onOemDone: () -> Unit,
    onBack: (() -> Unit)?,
    onSkipAll: (() -> Unit)?,
) {
    val copy = copyFor(step)
    Column(
        Modifier
            .fillMaxSize()
            .background(Eldering.palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(
            title = stringResource(R.string.perm_screen_title),
            subtitle = stringResource(R.string.perm_step_of, index + 1, total),
            onBack = onBack,
            backLabel = stringResource(R.string.action_back),
            actions = { if (onSkipAll != null) TextLinkButton(stringResource(R.string.perm_skip_to_summary), onSkipAll) },
        )
        LinearProgressIndicator(
            progress = { (index + 1f) / total },
            color = Eldering.palette.safe,
            trackColor = Eldering.palette.hairline,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Eldering.palette.surface),
            ) {
                Icon(copy.icon, contentDescription = null, tint = Eldering.palette.ink, modifier = Modifier.size(28.dp))
            }
            Text(
                stringResource(copy.title),
                style = Eldering.type.screenTitle,
                color = Eldering.palette.ink,
                modifier = Modifier.semantics { heading() },
            )
            StepStatusChip(status, essential = PermissionPlan.isEssential(step))
            if (step == PermissionStep.BACKGROUND_LOCATION) {
                // Prominent disclosure: shown in full, on its own page, before the system request.
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Eldering.palette.infoContainer,
                    border = BorderStroke(1.dp, Eldering.palette.me.copy(alpha = 0.35f)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.perm_background_disclosure_title),
                            style = Eldering.type.cardTitle,
                            color = Eldering.palette.infoText,
                        )
                        Text(stringResource(R.string.perm_background_disclosure), style = Eldering.type.body, color = Eldering.palette.infoText)
                    }
                }
            }
            Text(stringResource(copy.body), style = Eldering.type.body, color = Eldering.palette.inkMuted)
            if (step == PermissionStep.OEM_BACKGROUND) {
                DesignCard(verticalSpacing = 10.dp) {
                    listOf(R.string.perm_oem_step1, R.string.perm_oem_step2, R.string.perm_oem_step3).forEachIndexed { i, res ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Eldering.palette.ink),
                            ) { Text("${i + 1}", style = Eldering.type.microBold, color = Eldering.palette.onInk) }
                            Text(stringResource(res), style = Eldering.type.bodySmall, color = Eldering.palette.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesignButton(stringResource(R.string.perm_oem_app_info), onOemAppInfo, colors = DesignButtons.outline(), modifier = Modifier.weight(1f))
                    DesignButton(stringResource(R.string.perm_oem_auto_launch), onOemAutoLaunch, colors = DesignButtons.outline(), modifier = Modifier.weight(1f))
                }
            }
            if (blocked && status != StepStatus.DONE) {
                Text(stringResource(R.string.perm_blocked_hint), style = Eldering.type.caption, color = Eldering.palette.alertText)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            when {
                status == StepStatus.DONE -> DesignButton(
                    text = stringResource(R.string.action_next),
                    onClick = onNext,
                    colors = DesignButtons.safe(),
                    height = 56.dp,
                    textStyle = Eldering.type.buttonLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                step == PermissionStep.OEM_BACKGROUND -> DesignButton(
                    text = stringResource(R.string.perm_oem_done),
                    onClick = onOemDone,
                    height = 56.dp,
                    textStyle = Eldering.type.buttonLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> DesignButton(
                    text = stringResource(if (blocked) R.string.perm_open_settings else copy.action),
                    onClick = onPrimary,
                    enabled = status != StepStatus.BLOCKED,
                    height = 56.dp,
                    textStyle = Eldering.type.buttonLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (status != StepStatus.DONE) {
                TextLinkButton(stringResource(R.string.perm_not_now), onNext, color = Eldering.palette.inkMuted, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StepStatusChip(status: StepStatus, essential: Boolean) {
    val palette = Eldering.palette
    when (status) {
        StepStatus.DONE -> StatusChip(stringResource(R.string.perm_status_done), palette.safeContainer, palette.safeText, dotColor = palette.safe)
        StepStatus.BLOCKED -> StatusChip(stringResource(R.string.perm_status_blocked), palette.surfaceMuted, palette.inkSoft)
        StepStatus.TODO -> StatusChip(
            stringResource(if (essential) R.string.perm_status_needed else R.string.perm_status_recommended),
            if (essential) palette.alertContainer else palette.surfaceMuted,
            if (essential) palette.alertText else palette.inkSoft,
            dotColor = if (essential) palette.alert else null,
        )
    }
}

@Composable
private fun PermissionChecklist(
    steps: List<PermissionStep>,
    statuses: Map<PermissionStep, StepStatus>,
    onboarding: Boolean,
    onOpenStep: (PermissionStep) -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val allEssential = steps.filter(PermissionPlan::isEssential).all { statuses[it] == StepStatus.DONE }
    Column(
        Modifier
            .fillMaxSize()
            .background(Eldering.palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(
            title = stringResource(R.string.perm_screen_title),
            subtitle = stringResource(if (allEssential) R.string.perm_summary_ready else R.string.perm_summary_missing),
            onBack = onBack,
            backLabel = stringResource(R.string.action_back),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            steps.forEach { step ->
                val status = statuses.getValue(step)
                val copy = copyFor(step)
                Surface(
                    onClick = { onOpenStep(step) },
                    shape = RoundedCornerShape(16.dp),
                    color = Eldering.palette.surface,
                    border = BorderStroke(1.dp, Eldering.palette.divider),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        StatusDot(status)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(copy.title), style = Eldering.type.name, color = Eldering.palette.ink)
                            StepStatusChip(status, PermissionPlan.isEssential(step))
                        }
                        Icon(ElderingIcons.ChevronRight, contentDescription = null, tint = Eldering.palette.inkMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        DesignButton(
            text = stringResource(if (onboarding) R.string.perm_go_to_map else R.string.action_done),
            onClick = onDone,
            height = 56.dp,
            textStyle = Eldering.type.buttonLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun StatusDot(status: StepStatus) {
    val palette = Eldering.palette
    val (bg, icon) = when (status) {
        StepStatus.DONE -> palette.safe to ElderingIcons.Check
        StepStatus.TODO -> palette.alert to ElderingIcons.Warning
        StepStatus.BLOCKED -> palette.outline to ElderingIcons.Clock
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
