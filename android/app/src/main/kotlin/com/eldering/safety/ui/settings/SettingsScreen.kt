package com.eldering.safety.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eldering.safety.BuildConfig
import com.eldering.safety.R
import com.eldering.safety.data.repository.AuthRepository
import com.eldering.safety.debug.DebugSettingsSection
import com.eldering.safety.network.Session
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DesignCard
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.NavigationRow
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.navigation.Route
import com.eldering.safety.ui.theme.Eldering
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val auth: AuthRepository) : ViewModel() {
    val session: StateFlow<Session?> = auth.session.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val signingOut = MutableStateFlow(false)
    val isSigningOut: StateFlow<Boolean> = signingOut.asStateFlow()

    /** Signs out on this device right away (and revokes the session on the server when reachable). */
    fun signOut() {
        if (signingOut.value) return
        signingOut.value = true
        viewModelScope.launch {
            try {
                auth.logout()
            } finally {
                signingOut.value = false
            }
        }
    }
}

@Composable
fun SettingsRoute(onBack: () -> Unit, onNavigate: (Route) -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it.authRepository) }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val signingOut by viewModel.isSigningOut.collectAsStateWithLifecycle()
    SettingsScreen(session, signingOut, onBack, onNavigate, viewModel::signOut)
}

@Composable
fun SettingsScreen(
    session: Session?,
    signingOut: Boolean,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = Eldering.palette
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(stringResource(R.string.settings_title), onBack, stringResource(R.string.action_back))
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
        ) {
            DesignCard {
                Text(
                    stringResource(R.string.settings_account),
                    style = Eldering.type.sectionTitle,
                    color = palette.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(session?.displayName.orEmpty(), style = Eldering.type.name, color = palette.ink)
                    Text(session?.email.orEmpty(), style = Eldering.type.caption, color = palette.inkMuted)
                }
                DesignButton(
                    stringResource(R.string.settings_sign_out),
                    { confirmSignOut = true },
                    colors = DesignButtons.outline(),
                    leadingIcon = ElderingIcons.LogOut,
                    loading = signingOut,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            NavigationRow(stringResource(R.string.settings_family), { onNavigate(Route.Family) }, subtitle = stringResource(R.string.settings_family_hint), icon = ElderingIcons.Users)
            NavigationRow(stringResource(R.string.contacts_title), { onNavigate(Route.Contacts) }, subtitle = stringResource(R.string.settings_contacts_hint), icon = ElderingIcons.Message)
            NavigationRow(stringResource(R.string.sharing_title), { onNavigate(Route.Sharing) }, subtitle = stringResource(R.string.settings_sharing_hint), icon = ElderingIcons.Shield)
            NavigationRow(
                stringResource(R.string.perm_screen_title),
                { onNavigate(Route.Permissions(onboarding = false)) },
                subtitle = stringResource(R.string.settings_permissions_hint),
                icon = ElderingIcons.Battery,
            )
            DebugSettingsSection()
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = Eldering.type.micro,
                color = palette.inkMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
