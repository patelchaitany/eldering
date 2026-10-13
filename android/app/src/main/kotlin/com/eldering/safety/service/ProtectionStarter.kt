package com.eldering.safety.service

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.eldering.safety.core.appGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starting protection from the app (the only place a `location` FGS may start without background location). */
object ProtectionStarter {

    /**
     * The permission walkthrough was finished (onboarding or Settings → Permissions & battery): arm protection
     * and start it now, while the app is in front.
     */
    fun arm(context: Context) {
        ProtectionPrefs.setArmed(context, true)
        SafetyService.start(context, reason = "permissions", fromForeground = true)
    }

    /**
     * Called from `MainActivity.onStart`: (re)starts the service from the foreground so it gets the `location`
     * type and recovers from a force-stop, and re-registers activity transitions (Play services may drop them).
     * Users who finished the walkthrough before protection existed are armed here.
     */
    fun onAppVisible(activity: ComponentActivity) {
        val ctx = activity.applicationContext
        activity.lifecycleScope.launch {
            var armed = ProtectionPrefs.isArmed(ctx)
            if (!armed && ctx.appGraph.appPreferences.permissionsWalkthroughDone.first()) {
                ProtectionPrefs.setArmed(ctx, true)
                armed = true
            }
            if (!armed) return@launch
            SafetyService.start(activity, reason = "app", fromForeground = true)
            TransitionRegistrar.register(ctx)
        }
    }
}
