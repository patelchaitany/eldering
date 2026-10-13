package com.eldering.safety.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eldering.safety.R
import com.eldering.safety.data.repository.FamilyRepository
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.FamilyDto
import com.eldering.safety.network.FamilyMemberDto
import com.eldering.safety.network.FamilyRole
import com.eldering.safety.ui.common.UiText
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.common.asString
import com.eldering.safety.ui.common.toUiText
import com.eldering.safety.ui.components.Avatar
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DesignCard
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.EmptyState
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.FormField
import com.eldering.safety.ui.components.IconCircleButton
import com.eldering.safety.ui.components.LoadingBox
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.components.initialsOf
import com.eldering.safety.ui.navigation.Route
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.MemberColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilyBlock(val family: FamilyDto, val members: List<FamilyMemberDto>)

data class FamilyManageUiState(
    val loading: Boolean = true,
    val families: List<FamilyBlock> = emptyList(),
    val busy: Boolean = false,
    val error: UiText? = null,
)

/** Admins rename, invite and remove; anyone can leave (SPEC RBAC). Admins get no extra location visibility. */
class FamilyManageViewModel(private val repository: FamilyRepository) : ViewModel() {
    private val state = MutableStateFlow(FamilyManageUiState())
    val uiState: StateFlow<FamilyManageUiState> = state.asStateFlow()

    private val left = Channel<Unit>(Channel.BUFFERED)

    /** Emits when I left my last family (the app then offers to create or join one). */
    val leftAllFamilies: Flow<Unit> = left.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            state.update { it.copy(loading = it.families.isEmpty(), error = null) }
            when (val r = repository.refresh()) {
                is ApiResult.Success -> {
                    val blocks = r.value.map { family ->
                        val members = (repository.members(family.id) as? ApiResult.Success)?.value.orEmpty()
                        FamilyBlock(family, members.sortedWith(compareBy({ !it.isMe }, { it.role != FamilyRole.ADMIN }, { it.displayName.lowercase() })))
                    }
                    state.update { it.copy(loading = false, families = blocks) }
                }
                is ApiResult.Failure -> state.update { it.copy(loading = false, error = r.error.toUiText()) }
            }
        }
    }

    fun rename(familyId: String, name: String) = act { repository.rename(familyId, name) }

    fun remove(familyId: String, userId: String) = act { repository.removeMember(familyId, userId) }

    fun leave(familyId: String) = act {
        val result = repository.leave(familyId)
        if (result is ApiResult.Success && state.value.families.size <= 1) left.send(Unit)
        result
    }

    private fun act(call: suspend () -> ApiResult<*>) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = call()
            state.update { it.copy(busy = false, error = (result as? ApiResult.Failure)?.error?.toUiText()) }
            if (result is ApiResult.Success) load()
        }
    }
}

private sealed interface PendingConfirm {
    data class Remove(val familyId: String, val member: FamilyMemberDto) : PendingConfirm
    data class Leave(val family: FamilyDto) : PendingConfirm
    data class Rename(val family: FamilyDto) : PendingConfirm
}

@Composable
fun FamilyManageRoute(onBack: () -> Unit, onNavigate: (Route) -> Unit) {
    val viewModel = appViewModel { FamilyManageViewModel(it.familyRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.leftAllFamilies.collect { onNavigate(Route.FamilySetup(canGoBack = false)) } }
    val palette = Eldering.palette
    var pending by remember { mutableStateOf<PendingConfirm?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(stringResource(R.string.family_manage_title), onBack, stringResource(R.string.action_back))
        if (state.loading) {
            LoadingBox(Modifier.weight(1f))
            return@Column
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
        ) {
            state.error?.let { ErrorBanner(it.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            if (state.families.isEmpty() && state.error == null) {
                EmptyState(
                    title = stringResource(R.string.invite_no_family_title),
                    body = stringResource(R.string.home_no_family),
                    icon = ElderingIcons.Users,
                )
            }
            state.families.forEach { block ->
                val family = block.family
                val admin = family.role == FamilyRole.ADMIN
                DesignCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(family.name, style = Eldering.type.cardTitle, color = palette.ink, modifier = Modifier.semantics { heading() })
                            Text(
                                stringResource(if (admin) R.string.family_manage_you_admin else R.string.family_manage_you_member),
                                style = Eldering.type.caption,
                                color = palette.inkMuted,
                            )
                        }
                        if (admin) {
                            IconCircleButton(ElderingIcons.Edit, stringResource(R.string.family_manage_rename), { pending = PendingConfirm.Rename(family) })
                        }
                    }
                    block.members.forEach { member ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Avatar(initialsOf(member.displayName), if (member.isMe) MemberColors.me else MemberColors.forUser(member.userId), size = 32.dp, ring = false)
                            Text(
                                if (member.isMe) stringResource(R.string.family_manage_member_you, member.displayName) else member.displayName,
                                style = Eldering.type.bodySmallStrong,
                                color = palette.ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(if (member.role == FamilyRole.ADMIN) R.string.role_admin else R.string.role_member),
                                style = Eldering.type.microBold,
                                color = if (member.role == FamilyRole.ADMIN) palette.adminText else palette.inkSoft,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (member.role == FamilyRole.ADMIN) palette.adminContainer else palette.surfaceMuted)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            if (admin && !member.isMe) {
                                IconCircleButton(
                                    ElderingIcons.Close,
                                    stringResource(R.string.family_manage_remove, member.displayName),
                                    { pending = PendingConfirm.Remove(family.id, member) },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (admin) {
                            DesignButton(
                                stringResource(R.string.home_invite),
                                { onNavigate(Route.Invite(familyId = family.id)) },
                                height = 48.dp,
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        DesignButton(
                            stringResource(R.string.family_manage_leave),
                            { pending = PendingConfirm.Leave(family) },
                            colors = DesignButtons.outline(),
                            height = 48.dp,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            DesignButton(
                stringResource(R.string.family_manage_join_another),
                { onNavigate(Route.FamilySetup(canGoBack = true)) },
                colors = DesignButtons.muted(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    when (val p = pending) {
        null -> Unit
        is PendingConfirm.Remove -> ConfirmDialog(
            title = stringResource(R.string.family_manage_remove_title, p.member.displayName),
            body = stringResource(R.string.family_manage_remove_body, p.member.displayName),
            confirm = stringResource(R.string.action_remove),
            onConfirm = { viewModel.remove(p.familyId, p.member.userId) },
            onDismiss = { pending = null },
        )
        is PendingConfirm.Leave -> ConfirmDialog(
            title = stringResource(R.string.family_manage_leave_title, p.family.name),
            body = stringResource(R.string.family_manage_leave_body),
            confirm = stringResource(R.string.family_manage_leave),
            onConfirm = { viewModel.leave(p.family.id) },
            onDismiss = { pending = null },
        )
        is PendingConfirm.Rename -> RenameDialog(p.family, onRename = { viewModel.rename(p.family.id, it) }, onDismiss = { pending = null })
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RenameDialog(family: FamilyDto, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(family.name) }
    val valid = name.trim().length in 1..80
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.family_manage_rename)) },
        text = {
            FormField(value = name, onValueChange = { name = it.take(90) }, label = stringResource(R.string.family_name_label))
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onDismiss()
                onRename(name.trim())
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
