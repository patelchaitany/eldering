/*
 * Debug-build-only tooling (src/debug, never in release): DebugInjectReceiver
 * (`com.eldering.safety.DEBUG_INJECT`, replays a synthetic trace through the live detector), DebugStatusReceiver
 * (`DEBUG_STATUS`, logs the service state to tag EldDebug), DebugModeReceiver (`DEBUG_MODE`, forces the movement
 * mode), DebugProtectionReceiver (`DEBUG_START` / `DEBUG_STOP`) — see DebugReceivers.kt for the adb commands —,
 * the debug settings section ("Allow real SMS") and UiGalleryActivity (every screen with sample data).
 */
package com.eldering.safety.debug
