package com.eldering.safety.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations (Navigation Compose). */
sealed interface Route {
    /** Signed-out start: what the app does, create account / log in. */
    @Serializable
    data object Welcome : Route

    @Serializable
    data class Auth(val register: Boolean) : Route

    /** Create a family or join one with an invite code. [canGoBack] is false during onboarding. */
    @Serializable
    data class FamilySetup(val canGoBack: Boolean = false) : Route

    /**
     * Shows (or creates, for admins) an invite code. [code] is pre-filled right after creating a family;
     * [expiresAtMs] 0 means unknown. [onboarding] adds "Continue" to the permission walkthrough.
     */
    @Serializable
    data class Invite(
        val familyId: String? = null,
        val code: String? = null,
        val expiresAtMs: Long = 0L,
        val onboarding: Boolean = false,
    ) : Route

    /** Permission walkthrough. During [onboarding] it runs step by step; otherwise it opens on the checklist. */
    @Serializable
    data class Permissions(val onboarding: Boolean) : Route

    @Serializable
    data object Home : Route

    /**
     * Trip to past. No [userId] = my own last hour (on-device trail); otherwise that member's trail from
     * [incidentId] (or from their active incident, if any).
     */
    @Serializable
    data class Trip(val userId: String? = null, val incidentId: String? = null, val name: String? = null) : Route

    /** What family sees: a family member's accident or SOS. */
    @Serializable
    data class FamilyAlert(val incidentId: String) : Route

    /** Hold-to-SOS result: the alert screen in its "sent" state. */
    @Serializable
    data object Sos : Route

    @Serializable
    data object Sharing : Route

    @Serializable
    data object Contacts : Route

    @Serializable
    data object Settings : Route

    /** Family management: rename, invite, remove members, leave. */
    @Serializable
    data object Family : Route
}
