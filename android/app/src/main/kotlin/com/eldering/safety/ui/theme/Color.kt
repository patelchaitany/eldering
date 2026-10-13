package com.eldering.safety.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Design tokens taken from docs/design/*.dc.html.
internal val Green700 = Color(0xFF0E6B57) // "safe": protection on, switches, primary status
internal val Green900 = Color(0xFF0B4E3F)
internal val Green100 = Color(0xFFDDEFE8)
internal val Green200 = Color(0xFF9BE3C9)
internal val Blue600 = Color(0xFF1D5FD1) // "me": my avatar, scrubber cursor
internal val Blue800 = Color(0xFF173F86) // "Sharing" chip
internal val Blue100 = Color(0xFFE3ECF8)
internal val Teal700 = Color(0xFF0B5D7A) // links
internal val AlertOrange = Color(0xFFC2410C) // accident / SOS
internal val AlertOrange100 = Color(0xFFFFE7DA)
internal val AlertBrown = Color(0xFF4A2C1C)
internal val Ink = Color(0xFF13201B)
internal val InkMuted = Color(0xFF4F5C57)
internal val InkSoft = Color(0xFF3B4743)
internal val Canvas = Color(0xFFF3F5F4)
internal val SurfaceMuted = Color(0xFFECEFEE)
internal val Hairline = Color(0xFFD5DCD9)
internal val Outline = Color(0xFFA9B4B0)

internal val LightColors = lightColorScheme(
    primary = Green700,
    onPrimary = Color.White,
    primaryContainer = Green100,
    onPrimaryContainer = Green900,
    secondary = Blue800,
    onSecondary = Color.White,
    secondaryContainer = Blue100,
    onSecondaryContainer = Blue800,
    tertiary = Teal700,
    onTertiary = Color.White,
    error = AlertOrange,
    onError = Color.White,
    errorContainer = AlertOrange100,
    onErrorContainer = AlertBrown,
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = InkMuted,
    outline = Outline,
    outlineVariant = Hairline,
)

internal val DarkColors = darkColorScheme(
    primary = Green200,
    onPrimary = Color(0xFF00382B),
    primaryContainer = Green900,
    onPrimaryContainer = Green100,
    secondary = Color(0xFFADC6FF),
    onSecondary = Color(0xFF002E69),
    secondaryContainer = Blue800,
    onSecondaryContainer = Blue100,
    tertiary = Color(0xFF8BD0EE),
    onTertiary = Color(0xFF003546),
    error = Color(0xFFFDBA8C),
    onError = Color(0xFF4A2C1C),
    errorContainer = Color(0xFF7A2E0A),
    onErrorContainer = AlertOrange100,
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFE4EAE7),
    surface = Color(0xFF161D1A),
    onSurface = Color(0xFFE4EAE7),
    surfaceVariant = Color(0xFF3B4743),
    onSurfaceVariant = Color(0xFFBFC9C5),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3B4743),
)

/**
 * The design's named colors (docs/design). Screens use these rather than raw hex so light and dark stay
 * consistent. Status colors keep their meaning in both themes: green = protected/safe, orange = accident/SOS,
 * blue = me.
 */
@Immutable
data class ElderingPalette(
    /** Screen background (#F3F5F4). */
    val ground: Color,
    /** Cards, header buttons (#FFFFFF). */
    val surface: Color,
    /** Disabled/paused card background (#F7F8F8). */
    val surfaceDim: Color,
    /** Segmented-control track, neutral chip (#ECEFEE). */
    val surfaceMuted: Color,
    /** Primary text and dark buttons (#13201B). */
    val ink: Color,
    /** Text on [ink] backgrounds. */
    val onInk: Color,
    /** Secondary text (#4F5C57). */
    val inkMuted: Color,
    /** Tertiary text, neutral chip text (#3B4743). */
    val inkSoft: Color,
    /** Disabled text (#8A9590). */
    val inkDisabled: Color,
    /** Button and map borders (#D5DCD9). */
    val hairline: Color,
    /** Card borders and dividers (#DDE3E0). */
    val divider: Color,
    /** Inner dividers (#ECEFEE). */
    val dividerSoft: Color,
    /** Switch-off track, dashed borders (#A9B4B0). */
    val outline: Color,
    val safe: Color,
    val safeText: Color,
    val safeContainer: Color,
    val alert: Color,
    val onAlert: Color,
    val alertContainer: Color,
    val alertText: Color,
    val me: Color,
    val meText: Color,
    val meContainer: Color,
    /** "Your 1-hour trail" info card (#E9EEF5 / #22385F). */
    val infoContainer: Color,
    val infoText: Color,
    val adminContainer: Color,
    val adminText: Color,
    val emergencyContainer: Color,
    val emergencyText: Color,
    val link: Color,
    /** Schematic map fallback colors. */
    val mapGround: Color,
    val mapPark: Color,
    val mapWater: Color,
    val mapRoad: Color,
)

internal val LightPalette = ElderingPalette(
    ground = Canvas,
    surface = Color.White,
    surfaceDim = Color(0xFFF7F8F8),
    surfaceMuted = SurfaceMuted,
    ink = Ink,
    onInk = Color.White,
    inkMuted = InkMuted,
    inkSoft = InkSoft,
    inkDisabled = Color(0xFF8A9590),
    hairline = Hairline,
    divider = Color(0xFFDDE3E0),
    dividerSoft = SurfaceMuted,
    outline = Outline,
    safe = Green700,
    safeText = Green900,
    safeContainer = Green100,
    alert = AlertOrange,
    onAlert = Color.White,
    alertContainer = AlertOrange100,
    alertText = AlertBrown,
    me = Blue600,
    meText = Blue800,
    meContainer = Blue100,
    infoContainer = Color(0xFFE9EEF5),
    infoText = Color(0xFF22385F),
    adminContainer = Color(0xFFEFE6F8),
    adminText = Color(0xFF5A2C8C),
    emergencyContainer = Color(0xFFF7ECDD),
    emergencyText = Color(0xFF6B4410),
    link = Teal700,
    mapGround = Color(0xFFE4EAE7),
    mapPark = Color(0xFFD2E3D2),
    mapWater = Color(0xFFC6DAE4),
    mapRoad = Color.White,
)

internal val DarkPalette = ElderingPalette(
    ground = Color(0xFF0F1513),
    surface = Color(0xFF161D1A),
    surfaceDim = Color(0xFF131917),
    surfaceMuted = Color(0xFF232B28),
    ink = Color(0xFFE4EAE7),
    onInk = Color(0xFF0F1513),
    inkMuted = Color(0xFFBFC9C5),
    inkSoft = Color(0xFFA9B4B0),
    inkDisabled = Color(0xFF6B7672),
    hairline = Color(0xFF3B4743),
    divider = Color(0xFF2C3632),
    dividerSoft = Color(0xFF232B28),
    outline = Color(0xFF6B7672),
    safe = Color(0xFF3FA88E),
    safeText = Green200,
    safeContainer = Color(0xFF123B31),
    alert = Color(0xFFE0611F),
    onAlert = Color.White,
    alertContainer = Color(0xFF4A2210),
    alertText = AlertOrange100,
    me = Color(0xFF5B8DEB),
    meText = Color(0xFFADC6FF),
    meContainer = Color(0xFF1A2D52),
    infoContainer = Color(0xFF1A2536),
    infoText = Color(0xFFC9D6EC),
    adminContainer = Color(0xFF2E2140),
    adminText = Color(0xFFD9C2F2),
    emergencyContainer = Color(0xFF3A2C17),
    emergencyText = Color(0xFFF0D3A8),
    link = Color(0xFF8BD0EE),
    mapGround = Color(0xFF1F2725),
    mapPark = Color(0xFF243328),
    mapWater = Color(0xFF1E2E38),
    mapRoad = Color(0xFF2E3834),
)

/** The accident countdown is always dark (design: CrashAlert), whatever the system theme. */
object AlertScreenColors {
    val background = Color(0xFF1F120C)
    val card = Color(0xFF2E1A10)
    val cardBorder = AlertBrown
    val accent = Color(0xFFFDBA8C)
    val text = Color.White
    val textMuted = Color(0xFFF1DCD0)
    val ring = AlertOrange
    val good = Green200
}

/** Avatar colors for family members (design: Dad #0E6B57, Mom #7A3FB8, Uncle #8A5A12); me is always blue. */
object MemberColors {
    val me = Blue600
    val unavailable = Color(0xFF6B7672)

    /** SMS-only emergency contacts (design: Uncle). */
    val emergencyContact = Color(0xFF8A5A12)
    private val palette = listOf(
        Color(0xFF0E6B57),
        Color(0xFF7A3FB8),
        emergencyContact,
        Color(0xFF0B5D7A),
        Color(0xFFA23B72),
        Color(0xFF4D6A12),
    )

    /** Stable color for a user id, so a person keeps their color across screens and launches. */
    fun forUser(userId: String): Color = palette[Math.floorMod(userId.hashCode(), palette.size)]
}
