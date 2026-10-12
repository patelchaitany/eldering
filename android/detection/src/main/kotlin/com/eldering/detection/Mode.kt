package com.eldering.detection

/**
 * Movement context reported by the app (Activity Recognition transitions and speed).
 * Detection stays armed in every mode; the mode only selects thresholds and gates.
 */
public enum class Mode { STILL, WALKING, RUNNING, CYCLING, VEHICLE, UNKNOWN }
