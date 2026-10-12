package com.eldering.safety.ui.common

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.eldering.safety.R
import com.eldering.safety.network.ApiError
import kotlin.math.ceil

/**
 * Text a ViewModel wants shown, resolved against resources only in the UI (ViewModels stay free of Context and
 * locale). Arguments may themselves be [UiText].
 */
@Immutable
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Already-localized or user-provided text (names, server-provided landmarks). */
    data class Raw(val value: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.map { it.resolve() }.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, count, *args.map { it.resolve() }.toTypedArray())
    is UiText.Raw -> value
}

@Composable
private fun Any.resolve(): Any = if (this is UiText) asString() else this

/** Non-composable resolution (e.g. inside click handlers that build share text). */
fun UiText.resolve(resources: android.content.res.Resources): String = when (this) {
    is UiText.Res -> resources.getString(id, *args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray())
    is UiText.Plural -> resources.getQuantityString(
        id,
        count,
        *args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray(),
    )
    is UiText.Raw -> value
}

@Composable
fun rememberResources(): android.content.res.Resources = LocalResources.current

/** Generic, user-facing message for an API failure. Screens map the codes they care about first. */
fun ApiError.toUiText(): UiText = when (this) {
    is ApiError.Network -> UiText.res(R.string.error_network)
    is ApiError.SignedOut -> UiText.res(R.string.error_signed_out)
    is ApiError.Unexpected -> UiText.res(R.string.error_unexpected)
    is ApiError.Http -> when {
        isRateLimited -> {
            val minutes = retryAfter?.let { ceil(it.seconds / 60.0).toInt().coerceAtLeast(1) } ?: 1
            UiText.Plural(R.plurals.error_rate_limited, minutes)
        }
        isUnauthorized -> UiText.res(R.string.error_signed_out)
        isForbidden -> UiText.res(R.string.error_forbidden)
        isNotFound -> UiText.res(R.string.error_not_found)
        status >= 500 -> UiText.res(R.string.error_server)
        else -> UiText.res(R.string.error_unexpected)
    }
}
