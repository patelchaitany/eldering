package com.eldering.safety.ui.platform

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.eldering.safety.data.repository.PhoneNumbers
import com.eldering.safety.ui.contacts.PickPhoneContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Call Dad": dials the known number (ACTION_DIAL, the user presses call). The server has no phone numbers, so when
 * none is known yet the system contact picker opens instead; the picked number is handed to [onNumberPicked] (to be
 * remembered on this phone) and dialled. Without a contacts app the dialer opens empty.
 */
@Composable
fun rememberCallPerson(onNumberPicked: (String) -> Unit): (phone: String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onNumberPicked)
    val picker = rememberLauncherForActivityResult(PickPhoneContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val phone = withContext(Dispatchers.IO) { PickPhoneContact.read(context, uri) }?.phone?.let(PhoneNumbers::normalize)
                ?: return@launch
            picked(phone)
            ExternalIntents.dial(context, phone)
        }
    }
    return remember(picker) {
        { phone ->
            if (phone != null) {
                ExternalIntents.dial(context, phone)
            } else {
                try {
                    picker.launch(Unit)
                } catch (e: ActivityNotFoundException) {
                    ExternalIntents.dial(context, null)
                }
            }
        }
    }
}
