package com.eldering.safety.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` for code that may suspend: returns null when [block] throws, but **rethrows cancellation**, so a
 * cancelled caller is never mistaken for a failed lookup (e.g. "no emergency contacts") and keeps unwinding.
 */
inline fun <T> catchingNonCancellation(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
