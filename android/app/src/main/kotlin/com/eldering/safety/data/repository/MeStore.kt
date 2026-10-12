package com.eldering.safety.data.repository

import com.eldering.safety.network.FamilyDto
import com.eldering.safety.network.MeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory cache of `GET /v1/me` shared by [AuthRepository], [FamilyRepository] and [SharingRepository], so a
 * mutation in one (join a family, pause sharing) is visible to every observer without another round trip.
 */
class MeStore {
    private val state = MutableStateFlow<MeResponse?>(null)

    val me: StateFlow<MeResponse?> = state.asStateFlow()

    fun set(me: MeResponse) {
        state.value = me
    }

    /** Applies [transform] if /me has been loaded; otherwise there is nothing to patch. */
    fun update(transform: (MeResponse) -> MeResponse) {
        state.update { current -> current?.let(transform) }
    }

    fun upsertFamily(family: FamilyDto) = update { me ->
        val index = me.families.indexOfFirst { it.id == family.id }
        me.copy(families = if (index < 0) me.families + family else me.families.toMutableList().also { it[index] = family })
    }

    fun removeFamily(familyId: String) = update { me -> me.copy(families = me.families.filterNot { it.id == familyId }) }

    fun clear() {
        state.value = null
    }
}
