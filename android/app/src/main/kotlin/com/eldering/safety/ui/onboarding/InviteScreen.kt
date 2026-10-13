package com.eldering.safety.ui.onboarding

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.common.dateTimeText
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DesignCard
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.EmptyState
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.LoadingBox
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.components.SuccessBanner
import com.eldering.safety.ui.platform.ExternalIntents
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.ElderingTheme
import kotlinx.coroutines.launch

@Composable
fun InviteRoute(
    familyId: String?,
    code: String?,
    expiresAtMs: Long,
    onboarding: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val viewModel = appViewModel { InviteViewModel(familyId, code, expiresAtMs, it.familyRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    InviteScreen(
        state = state,
        onboarding = onboarding,
        onBack = onBack,
        onContinue = onContinue,
        onNewCode = viewModel::newCode,
        onRetry = viewModel::retry,
    )
}

@Composable
fun InviteScreen(
    state: InviteUiState,
    onboarding: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onNewCode: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Eldering.palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(
            title = stringResource(R.string.invite_title),
            subtitle = state.familyName,
            onBack = if (onboarding) null else onBack,
            backLabel = stringResource(R.string.action_back),
        )
        if (state.loading && state.code == null) {
            LoadingBox(Modifier.weight(1f))
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                state.error?.let { ErrorBanner(it.asString(), actionLabel = stringResource(R.string.action_retry), onAction = onRetry) }
                val code = state.code
                when {
                    code != null -> {
                        val shown = InviteViewModel.displayCode(code)
                        DesignCard {
                            Text(stringResource(R.string.invite_body), style = Eldering.type.bodySmall, color = Eldering.palette.inkMuted)
                            Text(
                                shown,
                                style = Eldering.type.code,
                                color = Eldering.palette.ink,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .semantics { contentDescription = code.toCharArray().joinToString(" ") },
                            )
                            if (state.expiresAtMs > 0) {
                                Text(
                                    stringResource(R.string.invite_valid_until, dateTimeText(state.expiresAtMs)),
                                    style = Eldering.type.caption,
                                    color = Eldering.palette.inkMuted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesignButton(
                                    text = stringResource(R.string.invite_share),
                                    leadingIcon = ElderingIcons.Share,
                                    onClick = {
                                        ExternalIntents.shareText(
                                            context,
                                            resources.getString(R.string.invite_share_text, state.familyName.orEmpty(), shown),
                                            resources.getString(R.string.invite_share),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                DesignButton(
                                    text = stringResource(if (copied) R.string.invite_copied else R.string.invite_copy),
                                    leadingIcon = if (copied) ElderingIcons.Check else ElderingIcons.Copy,
                                    colors = DesignButtons.outline(),
                                    contentDescription = stringResource(if (copied) R.string.invite_copied else R.string.invite_copy_a11y),
                                    onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(resources.getString(R.string.invite_clip_label), code)))
                                            copied = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (state.isAdmin) {
                            DesignButton(
                                text = stringResource(R.string.invite_new_code),
                                onClick = onNewCode,
                                loading = state.creating,
                                colors = DesignButtons.muted(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    state.familyName == null -> EmptyState(
                        title = stringResource(R.string.invite_no_family_title),
                        body = stringResource(R.string.invite_no_family_body),
                        icon = ElderingIcons.Users,
                    )
                    !state.isAdmin -> EmptyState(
                        title = stringResource(R.string.invite_not_admin_title),
                        body = if (state.adminNames.isEmpty()) {
                            stringResource(R.string.invite_not_admin_body_generic)
                        } else {
                            stringResource(R.string.invite_not_admin_body, state.adminNames.joinToString(", "))
                        },
                        icon = ElderingIcons.Users,
                    )
                }
                if (onboarding && state.code != null) {
                    SuccessBanner(stringResource(R.string.invite_family_created))
                }
            }
        }
        if (onboarding) {
            DesignButton(
                text = stringResource(R.string.action_continue),
                onClick = onContinue,
                height = 56.dp,
                textStyle = Eldering.type.buttonLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun InvitePreview() {
    ElderingTheme {
        InviteScreen(
            InviteUiState(loading = false, familyName = "Rivera family", isAdmin = true, code = "ABCD1234", expiresAtMs = 1_800_000_000_000),
            onboarding = true,
            onBack = {},
            onContinue = {},
            onNewCode = {},
            onRetry = {},
        )
    }
}
