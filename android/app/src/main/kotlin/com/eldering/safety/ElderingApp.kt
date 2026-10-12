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
    }
}
