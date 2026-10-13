/*
 * Background runtime (docs/research/android-background.md):
 * - SafetyService: foreground service (location|specialUse) hosting DetectionEngine (SensorPipeline → the live
 *   com.eldering.detection.AccidentDetector on its own thread), the activity-adaptive Fused-location trail,
 *   HeartbeatController (only while sharing is on; pending_alerts → family-alert notification) and the hand-off of
 *   detections to alert.AlertController.
 * - SensingPlans: pure per-mode sensor rates / batching / location requests / heartbeat pacing (SPEC "Battery").
 * - Receivers: activity transitions, boot + package-replaced restart, still-mode keep-alive alarm.
 * - ProtectionStarter / ProtectionPrefs: starting from the app and the synchronous "armed" flag for receivers.
 */
package com.eldering.safety.service
