package com.eldering.safety.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DesignCard
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.FormField
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.navigation.Route
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.ElderingTheme

@Composable
fun FamilySetupRoute(canGoBack: Boolean, onBack: () -> Unit, onNavigate: (Route) -> Unit) {
    val viewModel = appViewModel { FamilySetupViewModel(it.familyRepository, it.appPreferences) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.navigation.collect(onNavigate) }
    FamilySetupScreen(
        state = state,
        onBack = if (canGoBack) onBack else null,
        onFamilyName = viewModel::onFamilyName,
        onCode = viewModel::onCode,
        onCreate = viewModel::create,
        onJoin = viewModel::join,
    )
}

@Composable
fun FamilySetupScreen(
    state: FamilySetupUiState,
    onBack: (() -> Unit)?,
    onFamilyName: (String) -> Unit,
    onCode: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Eldering.palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        ScreenHeader(
            title = stringResource(R.string.family_setup_title),
            subtitle = stringResource(R.string.family_setup_subtitle),
            onBack = onBack,
            backLabel = stringResource(R.string.action_back),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            state.error?.let { ErrorBanner(it.asString()) }
            DesignCard {
                Text(stringResource(R.string.family_create_title), style = Eldering.type.cardTitle, color = Eldering.palette.ink)
                Text(stringResource(R.string.family_create_body), style = Eldering.type.caption, color = Eldering.palette.inkMuted)
                FormField(
                    value = state.familyName,
                    onValueChange = onFamilyName,
                    label = stringResource(R.string.family_name_label),
                    supporting = stringResource(R.string.family_name_hint),
                    error = state.nameError?.let { stringResource(it) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCreate() }),
                    enabled = !state.busy,
                )
                DesignButton(
                    text = stringResource(R.string.family_create_action),
                    onClick = onCreate,
                    loading = state.creating,
                    enabled = !state.joining,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DesignCard {
                Text(stringResource(R.string.family_join_title), style = Eldering.type.cardTitle, color = Eldering.palette.ink)
                Text(stringResource(R.string.family_join_body), style = Eldering.type.caption, color = Eldering.palette.inkMuted)
                FormField(
                    value = state.code,
                    onValueChange = onCode,
                    label = stringResource(R.string.family_code_label),
                    error = state.codeError?.let { stringResource(it) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onJoin() }),
                    enabled = !state.busy,
                )
                DesignButton(
                    text = stringResource(R.string.family_join_action),
                    onClick = onJoin,
                    colors = DesignButtons.outline(),
                    loading = state.joining,
                    enabled = !state.creating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FamilySetupPreview() {
    ElderingTheme { FamilySetupScreen(FamilySetupUiState(), null, {}, {}, {}, {}) }
}
