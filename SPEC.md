# Eldering — build contract (v1)

Android app that fuses phone sensors to detect accidents (vehicle crash, pedestrian impact, fall) while driving AND walking, alerts family with live location + a "trip to past" (last 1 hour trail with landmarks), and shows consenting family members on a map. Self-hosted backend with consent-based RBAC.

Background research: `reports/Android crash detection market.md`, `reports/Pedestrian accident detection fusion.md`. UI design: `docs/design/*.dc.html` (5 screens: Home family map, Accident countdown, Trip to past, What family sees, Sharing & privacy).

## Layout
```
SPEC.md            this contract (source of truth for interfaces)
docs/research/     focused research outputs feeding the build
server/            Go API + PostgreSQL/PostGIS (docker compose)
android/           Gradle root: :app (Android) + :detection (pure Kotlin/JVM)
```
Do not `git commit`/push unless the user asks. Never commit `SPEC.local.md`, `.env`, `deploy.env`, `local.properties` or test credentials.

## Environment (local Mac)
- JDK: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (JDK 25 is default — do NOT use it for Gradle/AGP).
- Android SDK: `~/Library/Android/sdk` (platforms 35+36, build-tools 35/36). Set `sdk.dir` in `android/local.properties`.
- Gradle 9.8.1 installed via brew (`gradle wrapper` to create the wrapper); use `./gradlew`.
- adb: `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`. Device: OnePlus test phone, Android 16 (SDK 36), OxygenOS/ColorOS. Sensors: Bosch BMI accel (≤400 Hz, FIFO 4500), gyro, magnetometer, proximity, significant-motion, step detector/counter. **No barometer** — pressure must be optional.
- Go 1.26 locally; Docker Desktop (may need a minute to start) and podman available.

## Server (shared VM — be careful)
- Host, SSH key, domain and the list of other services on the box are in **`SPEC.local.md`** (not committed) and `server/deploy/deploy.env`. Read them before any server work.
- Small VM: 2 vCPU, **~1 GB RAM** (≈450 MB free), Docker + compose v2, nginx + certbot. Other services on the box MUST NOT be touched.
- Our stack: `~/eldering-safety/` docker compose project `eldering` (postgis + api). API bound to **127.0.0.1:8095** only; Postgres not published (internal network only). Postgres tuned small (shared_buffers 64MB, max_connections 30). Secrets in `~/eldering-safety/.env` (chmod 600), generated on the server, never printed.
- nginx: new file `/etc/nginx/sites-available/eldering` (+ symlink) with `server_name <ELDERING_DOMAIN>;` → proxy to 127.0.0.1:8095; cert via `sudo certbot --nginx -d <ELDERING_DOMAIN>` only. Always `sudo nginx -t` before `sudo systemctl reload nginx`.
- Public base URL: `https://<ELDERING_DOMAIN>`. Android reads it from `BuildConfig.BASE_URL` (set `eldering.baseUrl` in `android/local.properties`).

## Data model (PostgreSQL 16 + PostGIS)
- `users(id uuid pk, email citext unique, password_hash, display_name, created_at)`
- `families(id uuid, name, created_by)`; `family_invites(code unique, family_id, created_by, expires_at, max_uses, uses)`
- `family_members(family_id, user_id, role 'admin'|'member', joined_at)` pk(family_id,user_id)
- `share_grants(sharer_id, viewer_id, level 'live'|'emergency'|'off', updated_at)` pk(sharer_id,viewer_id). **Only the sharer can write their rows.** Default when two users become co-members: `emergency` (no live location until the sharer opts in).
- `user_settings(user_id pk, sharing_state 'on'|'paused'|'off', paused_until, always_alert_on_accident bool default true)`
- `locations_latest(user_id pk, geom geography(Point,4326), accuracy_m, speed_mps, bearing_deg, activity, battery_pct, recorded_at, updated_at)` — server keeps ONLY the latest point, no history.
- `incidents(id uuid, user_id, kind 'vehicle_crash'|'pedestrian_impact'|'fall'|'sos', status 'active'|'resolved'|'cancelled', detected_at, geom, details jsonb, share_token_hash bytea, share_expires_at, resolved_at, resolved_by, resolution)`
- `incident_trail(incident_id, recorded_at, geom, accuracy_m, landmark text null)`; `incident_acks(incident_id, user_id, state 'seen'|'on_my_way', at)`
- `refresh_tokens(id, user_id, token_hash, expires_at, revoked_at)`
- Retention: incidents + trail hard-deleted 72 h after resolution/expiry (hourly job).

## RBAC rules (enforce in SQL with Row-Level Security AND in handlers)
- API connects as non-superuser role without BYPASSRLS; each request runs in a tx with `SET LOCAL app.user_id = '<uuid>'`. RLS ENABLED + FORCED on locations_latest, incidents, incident_trail, incident_acks, share_grants, user_settings.
- V can see S's **live location** iff V≠S share ≥1 family AND grant(S→V)='live' AND S.sharing_state='on' (or paused_until has passed).
- V can see S's **incidents/trail** iff they share a family AND grant(S→V) ∈ {live, emergency} AND (S.sharing_state='on' OR S.always_alert_on_accident). grant 'off' ⇒ never.
- Family `admin`: create invites, remove members, rename family. Admin gets NO extra location visibility.
- Anyone holding a valid incident share token (32 random bytes, base64url; only SHA-256 stored) may view that incident's latest location + trail until expiry (72 h) — nothing else.
- Leaving/removal from a family revokes visibility immediately (grants are only effective while co-members).

## HTTP API (JSON, prefix /v1, `Authorization: Bearer <access JWT>`; access 15 min, refresh 30 d rotating, argon2id passwords)
| Method | Path | Notes |
|---|---|---|
| POST | /v1/auth/register `{email,password,display_name}` | → `{access_token,refresh_token,user}` |
| POST | /v1/auth/login `{email,password}` | rate-limited |
| POST | /v1/auth/refresh `{refresh_token}` | rotates |
| POST | /v1/auth/logout `{refresh_token}` | |
| GET | /v1/me | user, settings, families[] |
| PATCH | /v1/me/settings `{sharing_state?,paused_until?,always_alert_on_accident?}` | |
| POST | /v1/families `{name}` | creator = admin; returns invite code |
| POST | /v1/families/{id}/invites | admin → `{code,expires_at}` (8 chars, 48 h) |
| POST | /v1/families/join `{code}` | |
| GET | /v1/families/{id}/members | `[{user_id,display_name,role,my_grant,their_grant}]` |
| DELETE | /v1/families/{id}/members/{userId} | admin, or self (leave) |
| GET | /v1/grants | my outgoing grants |
| PUT | /v1/grants/{viewerId} `{level}` | sharer = caller; viewer must be co-member |
| POST | /v1/location/heartbeat `{lat,lng,accuracy_m,speed_mps,bearing_deg,activity,battery_pct,recorded_at}` | → `{pending_alerts:[IncidentSummary],server_time}` (alerts I can see and haven't acked) |
| GET | /v1/family/locations | `[{user_id,display_name,status:'live'|'paused'|'hidden',lat?,lng?,accuracy_m?,speed_mps?,activity?,battery_pct?,recorded_at?}]` — coords only when visible |
| POST | /v1/incidents `{kind,detected_at,lat,lng,details,trail:[{lat,lng,recorded_at,accuracy_m,landmark?}]}` | → `{id,share_url,share_expires_at}` |
| POST | /v1/incidents/{id}/trail `{points:[...]}` | owner only (live updates) |
| POST | /v1/incidents/{id}/resolve `{resolution:'user_ok'|'false_alarm'|'handled'}` | owner, or viewer with access ('handled') |
| POST | /v1/incidents/{id}/ack `{state}` | viewer |
| GET | /v1/incidents/active | incidents visible to me |
| GET | /v1/incidents/{id} | detail + trail (RBAC) |
| GET | /s/{token} | public HTML (MapLibre GL JS + OpenFreeMap tiles) — live point + trail + landmarks; `noindex`, `no-store` |
| GET | /s/{token}/data | JSON for that page |
| GET | /healthz | |
Errors: `{error:{code,message}}`; 401/403/404 without leaking existence across users. Body limit 1 MB; per-IP rate limits on auth.

## Android app (com.eldering.safety, "Eldering")
- Kotlin 2.x, Jetpack Compose Material 3, minSdk 29, target/compile 36. Manual DI (`AppGraph`). Room, DataStore, Retrofit/OkHttp + kotlinx.serialization (or Ktor client), WorkManager, play-services-location (Fused location + Activity Recognition transitions), MapLibre Native Android with OpenFreeMap style `https://tiles.openfreemap.org/styles/liberty` (no API key).
- `:detection` — pure Kotlin JVM module, no Android imports:
  ```kotlin
  enum class SensorKind { ACCEL, GYRO, PRESSURE }           // ACCEL m/s² incl. gravity, GYRO rad/s, PRESSURE hPa in x
  data class SensorSample(val tNanos: Long, val kind: SensorKind, val x: Float, val y: Float, val z: Float)
  enum class Mode { STILL, WALKING, RUNNING, CYCLING, VEHICLE, UNKNOWN }
  enum class EventKind { VEHICLE_CRASH, PEDESTRIAN_IMPACT, FALL }
  data class DetectionEvent(val kind: EventKind, val confidence: Float, val triggeredAtNanos: Long, val features: Map<String, Float>, val reason: String)
  class AccidentDetector(val config: DetectorConfig = DetectorConfig(), val listener: (DetectionEvent) -> Unit) {
      fun onSample(s: SensorSample); fun onMode(mode: Mode, tNanos: Long); fun onSpeed(speedMps: Float, tNanos: Long)
      fun setAccelMaxRange(ms2: Float)
  }
  ```
  Staged fusion: cheap trigger (|a| over mode threshold or near saturation) → buffered window analysis (−2 s … +N s) → features (peak g, impacts-in-1 s, pre-impact free-fall, gyro rotation, gravity-vector orientation change, post-event stillness, speed drop, optional pressure delta) → mode-specific rules/score → suppression (phone-drop signature, quick pickup, no vehicle context for crash) and cooldown. Detection stays armed in ALL modes; mode only changes thresholds. Ships `SyntheticTraces` (walk, run, phone drop, sit-down, stairs, pothole, hard brake, fall, pedestrian impact, vehicle crash) used by unit tests and the debug injector.
- App pieces: `SafetyService` (foreground service; sensor batching per mode; feeds detector; heartbeat), activity-transition receiver + boot receiver, `TrailStore` (Room ring buffer, 65 min, activity-adaptive location sampling), `AlertController` (freeze trail → full-screen countdown → `IncidentSender`), `IncidentSender` (POST /v1/incidents with trail + Geocoder landmarks for ~10 key points; SMS to emergency contacts with share URL + maps link, offline fallback coords; live updates every 15 s for 60 min; WorkManager retries), emergency contacts stored on device only.
- Countdown: 15 s walking/fall, 20 s vehicle; "I'm OK" / "Send help now" / "Call 112" (`ACTION_DIAL` only — never auto-dial). After cancel: false-alarm feedback stored locally with the event window.
- **Test safety:** debug builds never send real SMS (dry-run log) unless the user enables "Allow real SMS" in a debug settings screen. Tests must never dial or text anyone.
- Debug-only receiver (in `src/debug` manifest): `adb shell am broadcast -a com.eldering.safety.DEBUG_INJECT --es scenario <name> -n com.eldering.safety/.debug.DebugInjectReceiver` replays a synthetic trace through the live detector.
- Screens (match the design): Onboarding (register/login, create/join family, permission walkthrough incl. background location disclosure, notifications, activity recognition, SMS, battery-optimization exemption + OnePlus guidance, full-screen intent), Home (map of visible family + list + status chips + hold-to-SOS), Accident countdown, Trip to past (trail + scrubber + landmark timeline), Family alert (viewer incident view: map, call, directions, ack), Sharing & privacy (sharing on/pause/off, always-alert, per-member Live/Emergency only/Off, roles), Emergency contacts.
- Battery: Still → accel 25 Hz batched (maxReportLatency ≈10 s), no GPS (passive only); Walking → accel+gyro 50 Hz batched, balanced location 30 s / 20 m; Vehicle → accel+gyro 100 Hz, balanced 5–10 s; Possible accident → full rate + high-accuracy 1–5 s. Heartbeat 60 s (walking) / 30 s (vehicle) only while sharing is on; none when still and unchanged. Target ≤1–2 %/h driving, ≤0.5 %/h idle.
