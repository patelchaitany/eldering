package com.eldering.safety.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Release builds have no debug settings (the debug variant's version lives in src/debug). */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DebugSettingsSection(modifier: Modifier = Modifier) = Unit
