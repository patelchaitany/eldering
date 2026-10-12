package com.eldering.safety.network

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Marks an endpoint that must be called without `Authorization` and must never trigger a token refresh
 * (auth endpoints, public share feed, health). Read by [AuthInterceptor] and [TokenAuthenticator] through
 * Retrofit's [retrofit2.Invocation] tag.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Unauthenticated

/**
 * Every endpoint of SPEC "HTTP API". Paths are relative to `AppConfig.baseUrl + "/"`.
 *
 * All functions throw on failure (Retrofit `HttpException` / `IOException`); call them through [apiCall] to get
 * an [ApiResult] with a mapped [ApiError].
 */
interface ElderingApi {

    // --- Auth -------------------------------------------------------------------------------------------------

    @Unauthenticated
    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    /** Rate-limited per IP and per account (429 + Retry-After). */
    @Unauthenticated
    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    /** Rotates the refresh token. Prefer [TokenRefresher], which serialises refreshes. */
    @Unauthenticated
    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): AuthResponse

    /** Always 204, even for an unknown token. */
    @Unauthenticated
    @POST("v1/auth/logout")
    suspend fun logout(@Body body: RefreshTokenRequest)

    // --- Me ---------------------------------------------------------------------------------------------------

    @GET("v1/me")
    suspend fun me(): MeResponse

    /** Body from [SettingsPatch.toJson] (absent vs explicit null matters for `paused_until`). */
    @PATCH("v1/me/settings")
    suspend fun updateSettings(@Body patch: JsonObject): SettingsDto

    // --- Families ---------------------------------------------------------------------------------------------

    /** The creator becomes admin; the response includes the first invite code. */
    @POST("v1/families")
    suspend fun createFamily(@Body body: FamilyNameRequest): CreateFamilyResponse

    /** Admin only. */
    @PATCH("v1/families/{id}")
    suspend fun renameFamily(@Path("id") familyId: String, @Body body: FamilyNameRequest): FamilyDto

    /** Admin only: 8-character code valid for 48 h. */
    @POST("v1/families/{id}/invites")
    suspend fun createInvite(@Path("id") familyId: String): InviteDto

    /** 404 `invite_invalid` for unknown, expired or used-up codes; joining twice is a no-op. */
    @POST("v1/families/join")
    suspend fun joinFamily(@Body body: JoinFamilyRequest): FamilyDto

    @GET("v1/families/{id}/members")
    suspend fun familyMembers(@Path("id") familyId: String): List<FamilyMemberDto>

    /** Admin removes anyone; a member removes themself (leave). 204. */
    @DELETE("v1/families/{id}/members/{userId}")
    suspend fun removeFamilyMember(@Path("id") familyId: String, @Path("userId") userId: String)

    // --- Grants -----------------------------------------------------------------------------------------------

    /** My outgoing grants towards every co-member, including implicit defaults. */
    @GET("v1/grants")
    suspend fun grants(): List<GrantDto>

    /** The caller is always the sharer; the viewer must be a co-member (404 otherwise). */
    @PUT("v1/grants/{viewerId}")
    suspend fun setGrant(@Path("viewerId") viewerId: String, @Body body: SetGrantRequest): GrantDto

    // --- Location ---------------------------------------------------------------------------------------------

    @POST("v1/location/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest): HeartbeatResponse

    @GET("v1/family/locations")
    suspend fun familyLocations(): List<MemberLocationDto>

    // --- Incidents --------------------------------------------------------------------------------------------

    /**
     * 201 for a new incident; 200 when [idempotencyKey] matches an incident already created (a retry), in which
     * case the share link is rotated and the old one stops working.
     */
    @POST("v1/incidents")
    suspend fun createIncident(
        @Header("Idempotency-Key") idempotencyKey: String?,
        @Body body: CreateIncidentRequest,
    ): CreateIncidentResponse

    /** Owner only; 409 `incident_closed` once resolved. Duplicate recorded_at values are ignored. */
    @POST("v1/incidents/{id}/trail")
    suspend fun appendIncidentTrail(
        @Path("id") incidentId: String,
        @Body body: AppendTrailRequest,
    ): AppendTrailResponse

    /** Owner (any resolution) or a viewer with access (`handled` only). */
    @POST("v1/incidents/{id}/resolve")
    suspend fun resolveIncident(
        @Path("id") incidentId: String,
        @Body body: ResolveIncidentRequest,
    ): IncidentSummaryDto

    /** Viewer only; `seen` never downgrades an existing `on_my_way`. */
    @POST("v1/incidents/{id}/ack")
    suspend fun ackIncident(@Path("id") incidentId: String, @Body body: AckIncidentRequest): AckDto

    @GET("v1/incidents/active")
    suspend fun activeIncidents(): List<IncidentSummaryDto>

    @GET("v1/incidents/{id}")
    suspend fun incident(@Path("id") incidentId: String): IncidentDetailDto

    // --- Public -----------------------------------------------------------------------------------------------

    /** The public share page (HTML for browsers). The app normally just opens the share URL. */
    @Unauthenticated
    @GET("s/{token}")
    suspend fun sharePage(@Path("token") token: String): ResponseBody

    @Unauthenticated
    @GET("s/{token}/data")
    suspend fun shareData(@Path("token") token: String): SharedIncidentDto

    @Unauthenticated
    @GET("healthz")
    suspend fun health(): HealthDto
}

/**
 * Blocking refresh call used by [TokenRefresher] from OkHttp threads. Built on the client *without* the auth
 * interceptor/authenticator so a refresh can never recurse into another refresh.
 */
interface TokenRefreshApi {
    @POST("v1/auth/refresh")
    fun refresh(@Body body: RefreshTokenRequest): Call<AuthResponse>
}
