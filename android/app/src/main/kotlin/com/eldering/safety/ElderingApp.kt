package com.eldering.safety

import android.app.Application
import com.eldering.safety.core.AppGraph

class ElderingApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Keep this cheap. In particular do NOT call MapLibre.getInstance() here: it loads libmaplibre.so,
        // slows the SafetyService process start and breaks Robolectric tests. Initialise it next to the MapView.
        graph = AppGraph(this)
        // An accident countdown, send or live hour that a killed process left behind resumes right away (not only
        // when something happens to touch the alert pipeline). One SharedPreferences lookup otherwise.
        if (graph.alertStore.hasPending()) graph.alertController
    }
}
