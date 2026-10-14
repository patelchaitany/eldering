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
     * Called by `MainActivity` while it is started and someone is signed in: (re)starts the service from the
     * foreground so it gets the `location` type and recovers from a force-stop, and re-registers activity
     * transitions (Play services may drop them). Users who finished the walkthrough are armed here, which is also
     * how protection comes back after a sign-out and the next sign-in. Nobody signed in: nothing starts (a
     * signed-out phone records no trail).
     */
    fun onAppVisible(activity: ComponentActivity) {
        val ctx = activity.applicationContext
        activity.lifecycleScope.launch {
            if (ctx.appGraph.sessionStore.current() == null) return@launch
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
