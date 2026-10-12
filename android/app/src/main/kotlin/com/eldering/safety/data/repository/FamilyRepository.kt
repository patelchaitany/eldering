package com.eldering.safety.data.repository

import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.network.ApiError
import com.eldering.safety.network.ApiResult
import com.eldering.safety.network.CreateFamilyResponse
import com.eldering.safety.network.ElderingApi
import com.eldering.safety.network.FamilyDto
import com.eldering.safety.network.FamilyMemberDto
import com.eldering.safety.network.FamilyNameRequest
import com.eldering.safety.network.InviteDto
import com.eldering.safety.network.JoinFamilyRequest
import com.eldering.safety.network.apiCall
import com.eldering.safety.network.map
import com.eldering.safety.network.onSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Families, invites and membership (SPEC: admins invite/remove/rename; anyone may leave). */
class FamilyRepository(
    private val api: ElderingApi,
    private val meStore: MeStore,
    private val sessionStore: SessionStore,
) {
    /** My families from the cached `/v1/me` (empty until [refresh] or another /me load). */
    val families: Flow<List<FamilyDto>> = meStore.me.map { it?.families.orEmpty() }.distinctUntilChanged()

    suspend fun refresh(): ApiResult<List<FamilyDto>> = apiCall { api.me() }.onSuccess(meStore::set).map { it.families }

    /** Creates a family with me as admin; the response carries the first invite code. */
    suspend fun create(name: String): ApiResult<CreateFamilyResponse> =
        apiCall { api.createFamily(FamilyNameRequest(name.trim())) }.onSuccess { meStore.upsertFamily(it.family) }

    /** Admin only (403 otherwise). */
    suspend fun rename(familyId: String, name: String): ApiResult<FamilyDto> =
        apiCall { api.renameFamily(familyId, FamilyNameRequest(name.trim())) }.onSuccess(meStore::upsertFamily)

    /** Admin only: a fresh 8-character code, valid 48 h. */
    suspend fun createInvite(familyId: String): ApiResult<InviteDto> = apiCall { api.createInvite(familyId) }

    /** 404 `invite_invalid` for wrong, expired or used-up codes. Joining a family I am already in is a no-op. */
    suspend fun join(code: String): ApiResult<FamilyDto> =
        apiCall { api.joinFamily(JoinFamilyRequest(normalizeInviteCode(code))) }.onSuccess(meStore::upsertFamily)

    suspend fun members(familyId: String): ApiResult<List<FamilyMemberDto>> = apiCall { api.familyMembers(familyId) }

    /**
     * Admin: removes [userId]. Anyone: removes themself (leave). Visibility between the removed member and the
     * rest of the family ends immediately on the server.
     */
    suspend fun removeMember(familyId: String, userId: String): ApiResult<Unit> {
        val me = sessionStore.current()?.userId ?: return ApiResult.Failure(ApiError.SignedOut())
        return apiCall { api.removeFamilyMember(familyId, userId) }.onSuccess {
            if (userId == me) {
                meStore.removeFamily(familyId)
            } else {
                meStore.update { m ->
                    m.copy(families = m.families.map { f ->
                        if (f.id == familyId) f.copy(memberCount = (f.memberCount - 1).coerceAtLeast(1)) else f
                    })
                }
            }
        }
    }

    suspend fun leave(familyId: String): ApiResult<Unit> {
        val me = sessionStore.current()?.userId ?: return ApiResult.Failure(ApiError.SignedOut())
        return removeMember(familyId, me)
    }

    companion object {
        /**
         * Light clean-up of what people type ("abcd-1234 " → "ABCD1234"). The server additionally maps the
         * Crockford look-alikes (O→0, I/L→1).
         */
        fun normalizeInviteCode(raw: String): String =
            raw.trim().uppercase().filterNot { it == ' ' || it == '-' || it == '\t' }
    }
}
