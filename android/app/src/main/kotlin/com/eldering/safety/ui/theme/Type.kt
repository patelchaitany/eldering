package com.eldering.safety.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.eldering.safety.R

/** Google Play services font provider (certificates in res/values/font_certs.xml). */
internal val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val FigtreeFont = GoogleFont("Figtree")

/**
 * Figtree, the design font. Downloaded on first use via Play services; the system font is shown until it
 * arrives, so no screen (in particular the accident countdown) ever waits on it.
 */
internal val Figtree = FontFamily(
    Font(FigtreeFont, GoogleFontsProvider, FontWeight.Normal),
    Font(FigtreeFont, GoogleFontsProvider, FontWeight.Medium),
    Font(FigtreeFont, GoogleFontsProvider, FontWeight.SemiBold),
    Font(FigtreeFont, GoogleFontsProvider, FontWeight.Bold),
    Font(FigtreeFont, GoogleFontsProvider, FontWeight.ExtraBold),
)

private val Base = Typography()

private fun TextStyle.figtree(): TextStyle = copy(fontFamily = Figtree)

internal val ElderingTypography = Typography(
    displayLarge = Base.displayLarge.figtree(),
    displayMedium = Base.displayMedium.figtree(),
    displaySmall = Base.displaySmall.figtree(),
    headlineLarge = Base.headlineLarge.figtree().copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = Base.headlineMedium.figtree().copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = Base.headlineSmall.figtree().copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.figtree().copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.figtree().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.figtree().copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.figtree(),
    bodyMedium = Base.bodyMedium.figtree(),
    bodySmall = Base.bodySmall.figtree(),
    labelLarge = Base.labelLarge.figtree().copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.figtree().copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.figtree(),
)

private fun style(size: Int, weight: FontWeight, lineHeight: Float? = null, letterSpacingEm: Float? = null) = TextStyle(
    fontFamily = Figtree,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = lineHeight?.let { (size * it).sp } ?: TextStyle.Default.lineHeight,
    letterSpacing = letterSpacingEm?.em ?: TextStyle.Default.letterSpacing,
)

/** The design's text styles (px in the mockups → sp). Colors come from [ElderingPalette]. */
object ElderingTextStyles {
    /** "Family" (26/800, −0.02em). */
    val screenTitle = style(26, FontWeight.ExtraBold, lineHeight = 1.2f, letterSpacingEm = -0.02f)
    /** "Are you OK?" and the family alert headline (30/800). */
    val alertTitle = style(30, FontWeight.ExtraBold, lineHeight = 1.15f)
    /** Family alert headline (26/800). */
    val bannerTitle = style(26, FontWeight.ExtraBold, lineHeight = 1.2f)
    /** Sub-screen headers: "Sharing & privacy", "Dad · last 1 hour" (20/800). */
    val headerTitle = style(20, FontWeight.ExtraBold, lineHeight = 1.25f)
    /** Card titles: "Share my live location" (16/700). */
    val cardTitle = style(16, FontWeight.Bold, lineHeight = 1.3f)
    /** Section titles: "Members", "Who can see me" (15/700). */
    val sectionTitle = style(15, FontWeight.Bold, lineHeight = 1.3f)
    /** Names in lists (15/700). */
    val name = style(15, FontWeight.Bold, lineHeight = 1.3f)
    val body = style(15, FontWeight.Normal, lineHeight = 1.45f)
    val bodyStrong = style(15, FontWeight.SemiBold, lineHeight = 1.4f)
    val bodySmall = style(14, FontWeight.Normal, lineHeight = 1.45f)
    val bodySmallStrong = style(14, FontWeight.Bold, lineHeight = 1.4f)
    /** Secondary lines: "3 of 4 members sharing" (13). */
    val caption = style(13, FontWeight.Normal, lineHeight = 1.4f)
    val captionStrong = style(13, FontWeight.SemiBold, lineHeight = 1.35f)
    val captionBold = style(13, FontWeight.Bold, lineHeight = 1.35f)
    /** Smallest text: timestamps, battery (12). */
    val micro = style(12, FontWeight.Normal, lineHeight = 1.45f)
    val microBold = style(12, FontWeight.Bold, lineHeight = 1.35f)
    /** "POSSIBLE ACCIDENT" (13/700, 0.08em, uppercase in the string itself). */
    val overline = style(13, FontWeight.Bold, lineHeight = 1.3f, letterSpacingEm = 0.08f)
    val button = style(15, FontWeight.Bold, lineHeight = 1.2f)
    val buttonSmall = style(14, FontWeight.SemiBold, lineHeight = 1.2f)
    val buttonLarge = style(16, FontWeight.Bold, lineHeight = 1.2f)
    /** SOS button (18/800, 0.02em). */
    val sos = style(18, FontWeight.ExtraBold, lineHeight = 1.2f, letterSpacingEm = 0.02f)
    /** Countdown digits (72/800). */
    val countdown = style(72, FontWeight.ExtraBold, lineHeight = 1f)
    /** Avatar initials (14/800). */
    val initials = style(14, FontWeight.ExtraBold, lineHeight = 1f)
    /** Invite code. */
    val code = style(34, FontWeight.ExtraBold, lineHeight = 1.1f, letterSpacingEm = 0.12f)
}
