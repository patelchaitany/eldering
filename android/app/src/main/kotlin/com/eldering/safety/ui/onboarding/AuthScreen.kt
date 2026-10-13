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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.FormField
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.components.TextLinkButton
import com.eldering.safety.ui.navigation.StartDestination
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.ElderingTheme

@Composable
fun AuthRoute(register: Boolean, onBack: () -> Unit, onSignedIn: (StartDestination) -> Unit) {
    val viewModel = appViewModel { AuthViewModel(register, it.authRepository, it.appPreferences) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.signedIn.collect(onSignedIn) }
    AuthScreen(
        state = state,
        onBack = onBack,
        onName = viewModel::onName,
        onEmail = viewModel::onEmail,
        onPassword = viewModel::onPassword,
        onSubmit = viewModel::submit,
        onSwitchMode = { viewModel.setMode(!state.register) },
    )
}

@Composable
fun AuthScreen(
    state: AuthUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onSwitchMode: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Eldering.palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        ScreenHeader(
            title = stringResource(if (state.register) R.string.auth_title_register else R.string.auth_title_login),
            subtitle = stringResource(if (state.register) R.string.auth_subtitle_register else R.string.auth_subtitle_login),
            onBack = onBack,
            backLabel = stringResource(R.string.action_back),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state.register) {
                FormField(
                    value = state.name,
                    onValueChange = onName,
                    label = stringResource(R.string.auth_name),
                    supporting = stringResource(R.string.auth_name_hint),
                    error = state.nameError?.let { stringResource(it) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.semantics { contentType = ContentType.PersonFullName },
                )
            }
            FormField(
                value = state.email,
                onValueChange = onEmail,
                label = stringResource(R.string.auth_email),
                error = state.emailError?.let { stringResource(it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.semantics { contentType = ContentType.EmailAddress + ContentType.Username },
            )
            FormField(
                value = state.password,
                onValueChange = onPassword,
                label = stringResource(R.string.auth_password),
                supporting = if (state.register) stringResource(R.string.auth_password_hint) else null,
                error = state.passwordError?.let { stringResource(it) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focus.clearFocus()
                    onSubmit()
                }),
                trailingIcon = {
                    TextLinkButton(
                        text = stringResource(if (showPassword) R.string.auth_hide_password else R.string.auth_show_password),
                        onClick = { showPassword = !showPassword },
                        color = Eldering.palette.inkMuted,
                        contentDescription = stringResource(
                            if (showPassword) R.string.auth_hide_password_a11y else R.string.auth_show_password_a11y,
                        ),
                    )
                },
                modifier = Modifier.semantics {
                    contentType = if (state.register) ContentType.NewPassword else ContentType.Password
                },
            )
            state.error?.let { ErrorBanner(message = it.asString()) }
            DesignButton(
                text = stringResource(if (state.register) R.string.auth_submit_register else R.string.auth_submit_login),
                onClick = {
                    focus.clearFocus()
                    onSubmit()
                },
                loading = state.submitting,
                height = 56.dp,
                textStyle = Eldering.type.buttonLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            TextLinkButton(
                text = stringResource(if (state.register) R.string.auth_switch_to_login else R.string.auth_switch_to_register),
                onClick = onSwitchMode,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.auth_privacy_note),
                style = Eldering.type.micro,
                color = Eldering.palette.inkMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AuthPreview() {
    ElderingTheme {
        AuthScreen(AuthUiState(register = true, name = "Asha"), {}, {}, {}, {}, {}, {})
    }
}
