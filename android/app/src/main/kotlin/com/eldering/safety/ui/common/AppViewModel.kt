package com.eldering.safety.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eldering.safety.core.AppGraph
import com.eldering.safety.core.appGraph

/**
 * A ViewModel scoped to the current navigation entry (or activity), built from the [AppGraph] (manual DI).
 * [key] distinguishes several instances of the same class in one scope.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.appGraph
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}
