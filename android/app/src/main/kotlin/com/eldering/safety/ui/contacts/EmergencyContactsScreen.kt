package com.eldering.safety.ui.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldering.safety.R
import com.eldering.safety.data.db.EmergencyContact
import com.eldering.safety.data.repository.ContactsRepository
import com.eldering.safety.ui.common.appViewModel
import com.eldering.safety.ui.components.Avatar
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.DesignCard
import com.eldering.safety.ui.components.DesignSwitch
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.components.EmptyState
import com.eldering.safety.ui.components.ErrorBanner
import com.eldering.safety.ui.components.FormField
import com.eldering.safety.ui.components.IconCircleButton
import com.eldering.safety.ui.components.LoadingBox
import com.eldering.safety.ui.components.ScreenHeader
import com.eldering.safety.ui.components.initialsOf
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.MemberColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EmergencyContactsRoute(onBack: () -> Unit) {
    val viewModel = appViewModel { EmergencyContactsViewModel(it.contactsRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(PickPhoneContact()) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) { PickPhoneContact.read(context, uri) }
                if (picked != null) viewModel.fillFromPicker(picked.name, picked.phone)
            }
        }
    }
    EmergencyContactsScreen(
        state = state,
        onBack = onBack,
        onAdd = { viewModel.startAdd() },
        onPick = { picker.launch(Unit) },
        onEdit = viewModel::startEdit,
        onSms = viewModel::setSmsEnabled,
        onRemove = viewModel::remove,
        onName = viewModel::onName,
        onPhone = viewModel::onPhone,
        onRelation = viewModel::onRelation,
        onSave = viewModel::save,
        onCancel = viewModel::cancelForm,
    )
}

@Composable
fun EmergencyContactsScreen(
    state: ContactsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onPick: () -> Unit,
    onEdit: (EmergencyContact) -> Unit,
    onSms: (EmergencyContact, Boolean) -> Unit,
    onRemove: (EmergencyContact) -> Unit,
    onName: (String) -> Unit,
    onPhone: (String) -> Unit,
    onRelation: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = Eldering.palette
    var confirmRemove by remember { mutableStateOf<EmergencyContact?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        ScreenHeader(
            title = stringResource(R.string.contacts_title),
            subtitle = stringResource(R.string.contacts_subtitle),
            onBack = onBack,
            backLabel = stringResource(R.string.action_back),
        )
        if (state.loading) {
            LoadingBox(Modifier.weight(1f))
            return@Column
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.contacts_explainer), style = Eldering.type.caption, color = palette.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
            val form = state.form
            if (form != null) ContactFormCard(form, onPick, onName, onPhone, onRelation, onSave, onCancel)
            if (state.contacts.isEmpty() && form == null) {
                EmptyState(
                    title = stringResource(R.string.contacts_empty_title),
                    body = stringResource(R.string.contacts_empty_body),
                    icon = ElderingIcons.Message,
                )
            }
            state.contacts.forEach { contact ->
                DesignCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalSpacing = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(initialsOf(contact.name), MemberColors.emergencyContact, size = 36.dp, ring = false)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(contact.name, style = Eldering.type.name, color = palette.ink)
                            Text(
                                listOfNotNull(contact.relation, contact.phone).joinToString(" · "),
                                style = Eldering.type.micro,
                                color = palette.inkMuted,
                            )
                        }
                        IconCircleButton(ElderingIcons.Edit, stringResource(R.string.contacts_edit, contact.name), { onEdit(contact) })
                        IconCircleButton(ElderingIcons.Trash, stringResource(R.string.contacts_remove, contact.name), { confirmRemove = contact })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (contact.smsEnabled) R.string.contacts_sms_on else R.string.contacts_sms_off),
                            style = Eldering.type.caption,
                            color = palette.inkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        DesignSwitch(
                            checked = contact.smsEnabled,
                            onCheckedChange = { onSms(contact, it) },
                            contentDescription = stringResource(R.string.contacts_sms_switch, contact.name),
                        )
                    }
                }
            }
        }
        if (state.form == null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 18.dp),
            ) {
                DesignButton(
                    stringResource(R.string.contacts_pick),
                    onPick,
                    colors = DesignButtons.outline(),
                    enabled = state.canAdd,
                    modifier = Modifier.weight(1f),
                )
                DesignButton(
                    stringResource(R.string.contacts_add),
                    onAdd,
                    leadingIcon = ElderingIcons.Plus,
                    enabled = state.canAdd,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    confirmRemove?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.contacts_remove_title, contact.name)) },
            text = { Text(stringResource(R.string.contacts_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    onRemove(contact)
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ContactFormCard(
    form: ContactForm,
    onPick: () -> Unit,
    onName: (String) -> Unit,
    onPhone: (String) -> Unit,
    onRelation: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    DesignCard {
        Text(
            stringResource(if (form.editingId == null) R.string.contacts_form_add else R.string.contacts_form_edit),
            style = Eldering.type.cardTitle,
            color = Eldering.palette.ink,
            modifier = Modifier.semantics { heading() },
        )
        form.formError?.let { ErrorBanner(stringResource(it)) }
        FormField(
            value = form.name,
            onValueChange = onName,
            label = stringResource(R.string.contacts_name),
            error = form.nameError?.let { stringResource(it) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        FormField(
            value = form.phone,
            onValueChange = onPhone,
            label = stringResource(R.string.contacts_phone),
            supporting = stringResource(R.string.contacts_phone_hint),
            error = form.phoneError?.let { stringResource(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        )
        FormField(
            value = form.relation,
            onValueChange = onRelation,
            label = stringResource(R.string.contacts_relation),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        )
        if (form.editingId == null) {
            DesignButton(
                stringResource(R.string.contacts_pick),
                onPick,
                colors = DesignButtons.muted(),
                height = 48.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignButton(stringResource(R.string.action_cancel), onCancel, colors = DesignButtons.outline(), modifier = Modifier.weight(1f))
            DesignButton(stringResource(R.string.action_save), onSave, loading = form.saving, modifier = Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.contacts_limit, ContactsRepository.MAX_CONTACTS),
            style = Eldering.type.micro,
            color = Eldering.palette.inkMuted,
        )
    }
}

/** A name and number chosen in the system contacts app. */
data class PickedContact(val name: String?, val phone: String?)

/**
 * Picks one phone number. Picking from `Phone.CONTENT_URI` grants temporary read access to that single row, so
 * no READ_CONTACTS permission is needed and nothing else in the address book is visible to the app.
 */
class PickPhoneContact : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }

    companion object {
        /** Reads the picked row (call off the main thread). */
        fun read(context: Context, uri: Uri): PickedContact? = try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null,
            )?.use { cursor -> if (!cursor.moveToFirst()) null else PickedContact(cursor.getString(0), cursor.getString(1)) }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
