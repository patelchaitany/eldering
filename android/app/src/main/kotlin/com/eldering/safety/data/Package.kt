/*
 * Local persistence and repositories.
 *
 * - db: Room (trail_points ring buffer, detection_events, emergency_contacts, own_incidents, outbox).
 * - session: SessionStore (Preferences DataStore, tokens encrypted with an Android Keystore AES-GCM key).
 * - trail: TrailStore (65 min ring buffer, sampling policy, snapshot) and TrailDownsampler.
 * - outbox: OutboxProcessor (ordered, idempotent incident uploads) + WorkManager retry.
 * - repository: Auth, Family, Sharing, Location, Incident, Contacts and DetectionEvent repositories.
 */
package com.eldering.safety.data
