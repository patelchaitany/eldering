# Eldering

Android app that uses the phone's sensors to detect accidents — vehicle crashes, a pedestrian being hit, or a fall — and alerts family with your live location and the last hour of where you've been ("trip to the past"). Family members who choose to share appear on a map. The backend is self-hosted.

> Work in progress — not ready for real emergencies yet.

## Features
- **Accident detection** — fuses accelerometer, gyroscope, GPS speed and activity recognition; works while driving and walking.
- **Countdown before alerting** — "I'm OK" cancels; otherwise family gets an alert with location.
- **Trip to the past** — the last 1 hour of your route with landmarks, kept encrypted on the phone and uploaded only after an accident or SOS.
- **Consent-based sharing** — each person chooses who sees them: *Live*, *Emergency only* or *Off*. Enforced with PostgreSQL row-level security.
- **Battery aware** — sensor and GPS rates change with activity (still / walking / driving).

## Repository layout
| Path | What |
|---|---|
| `android/` | Kotlin + Jetpack Compose app (`:app`) and the pure-Kotlin detection engine (`:detection`) |
| `server/` | Go API + PostgreSQL/PostGIS (Docker Compose) |
| `docs/` | Research notes and UI designs |
| `reports/`, `research_notes/` | Market and sensor-fusion research |
| `SPEC.md` | Build contract: data model, API, access rules |

## Quick start

**Server**
```sh
cd server
cp .env.example .env               # fill in secrets (openssl rand -hex 32)
docker compose up -d
```
To deploy to your own VM, copy `server/deploy/deploy.env.example` to `server/deploy/deploy.env` and run `./deploy/deploy.sh app`.

**Android** (JDK 21, Android SDK 36)
```sh
cd android
echo "eldering.baseUrl=https://your-server.example.com" >> local.properties
./gradlew :detection:test :app:assembleDebug
```
