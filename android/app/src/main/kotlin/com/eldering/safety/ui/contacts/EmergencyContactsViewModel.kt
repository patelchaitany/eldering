package com.eldering.safety.ui.contacts

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldering.safety.R
import com.eldering.safety.data.db.EmergencyContact
import com.eldering.safety.data.repository.ContactsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The add/edit form. [editingId] null = adding. */
data class ContactForm(
    val editingId: Long? = null,
    val name: String = "",
    val phone: String = "",
    val relation: String = "",
    @StringRes val nameError: Int? = null,
    @StringRes val phoneError: Int? = null,
    @StringRes val formError: Int? = null,
    val saving: Boolean = false,
)

data class ContactsUiState(
    val loading: Boolean = true,
    val contacts: List<EmergencyContact> = emptyList(),
    /** Non-null while the add/edit form is open. */
    val form: ContactForm? = null,
) {
    val canAdd: Boolean get() = contacts.size < ContactsRepository.MAX_CONTACTS
}

/** Emergency SMS contacts, stored on this phone only (SPEC). */
class EmergencyContactsViewModel(private val repository: ContactsRepository) : ViewModel() {
    private val state = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.contacts.collect { list -> state.update { it.copy(loading = false, contacts = list) } }
        }
    }

    fun startAdd(name: String = "", phone: String = "") {
        if (!state.value.canAdd) return
        state.update { it.copy(form = ContactForm(name = name, phone = phone)) }
    }

    fun startEdit(contact: EmergencyContact) = state.update {
        it.copy(form = ContactForm(editingId = contact.id, name = contact.name, phone = contact.phone, relation = contact.relation.orEmpty()))
    }

    /** Fills the open form (or opens one) from the system contact picker. */
    fun fillFromPicker(name: String?, phone: String?) {
        val form = state.value.form ?: ContactForm()
        state.update {
            it.copy(form = form.copy(name = name ?: form.name, phone = phone ?: form.phone, nameError = null, phoneError = null, formError = null))
        }
    }

    fun onName(value: String) = updateForm { it.copy(name = value.take(ContactsRepository.MAX_NAME_LENGTH + 5), nameError = null, formError = null) }

    fun onPhone(value: String) = updateForm { it.copy(phone = value.take(24), phoneError = null, formError = null) }

    fun onRelation(value: String) = updateForm { it.copy(relation = value.take(40)) }

    fun cancelForm() = state.update { it.copy(form = null) }

    fun save() {
        val form = state.value.form ?: return
        if (form.saving) return
        updateForm { it.copy(saving = true) }
        viewModelScope.launch {
            val existing = form.editingId?.let { id -> state.value.contacts.firstOrNull { it.id == id } }
            val result = if (existing != null) {
                repository.update(existing.copy(name = form.name, phone = form.phone, relation = form.relation))
            } else {
                repository.add(form.name, form.phone, form.relation)
            }
            when (result) {
                is ContactsRepository.SaveResult.Saved -> state.update { it.copy(form = null) }
                is ContactsRepository.SaveResult.Invalid -> updateForm { applyProblem(it.copy(saving = false), result.problem) }
            }
        }
    }

    fun setSmsEnabled(contact: EmergencyContact, enabled: Boolean) {
        viewModelScope.launch { repository.update(contact.copy(smsEnabled = enabled)) }
    }

    fun remove(contact: EmergencyContact) {
        viewModelScope.launch { repository.remove(contact.id) }
    }

    private fun updateForm(transform: (ContactForm) -> ContactForm) = state.update { s -> s.form?.let { s.copy(form = transform(it)) } ?: s }

    companion object {
        fun applyProblem(form: ContactForm, problem: ContactsRepository.Problem): ContactForm = when (problem) {
            ContactsRepository.Problem.NAME_BLANK -> form.copy(nameError = R.string.contacts_error_name_blank)
            ContactsRepository.Problem.NAME_TOO_LONG -> form.copy(nameError = R.string.contacts_error_name_long)
            ContactsRepository.Problem.PHONE_INVALID -> form.copy(phoneError = R.string.contacts_error_phone)
            ContactsRepository.Problem.DUPLICATE_PHONE -> form.copy(phoneError = R.string.contacts_error_duplicate)
            ContactsRepository.Problem.TOO_MANY -> form.copy(formError = R.string.contacts_error_too_many)
            ContactsRepository.Problem.NOT_FOUND -> form.copy(formError = R.string.contacts_error_not_found)
        }
    }
}
