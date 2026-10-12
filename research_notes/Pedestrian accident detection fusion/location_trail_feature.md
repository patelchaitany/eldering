# "Trip to the Past": Pre-Accident Location Trail with Landmarks on Android

Research date: 2026-10-12. Scope: existing products, Android techniques for continuous low-power location history, landmark identification, sharing the trail at crash time, and privacy/policy. About 18 searches and fetches. Sources are cited inline. Claims drawn from general Android knowledge that were not checked this session are labelled as such and kept under Inferences or Gaps.

## 1. Existing products with similar features: does anyone share the pre-incident trail with responders?

### Takeaway
No product I found sends responders a timestamped "last hour" trail with landmarks. Crash and SOS products (Pixel, Apple, Life360, Noonlight, Garmin, RapidSOS) send the current or live location, sometimes with a live-tracking link. Apple Check In ("Full" level) comes closest: it shares the "route traveled" plus where the phone was last unlocked, but it sends this to a chosen contact, not to responders. Life360 and Google Maps Timeline keep history, but as separate features that are not attached to the crash alert. A pre-incident trail with landmarks, packaged for responders, therefore looks like a real gap in the market.

### Cited Findings
**Google Maps Timeline (current behaviour)**
- Google discontinued Timeline on the web and moved Timeline data from its cloud to users' devices during 2024–2025. The cloud deadline slipped from Dec 2024 to May 18, 2025, and some reports give June 9, 2025 — [TechRadar](https://www.techradar.com/computing/software/google-maps-is-about-to-get-a-big-privacy-boost-but-fans-of-timeline-may-lose-their-data); [Yahoo Tech](https://tech.yahoo.com/general/articles/google-puts-date-maps-timelines-230941507.html)
- Google said it would try to move up to 90 days of Timeline data to the first device connected after the deadline. Older data would be deleted. The default auto-delete is 3 months, and a user report (not official) says 18 or 36 months, or off, can be chosen — [Nextpit](https://nextpit.com/how-to-save-google-maps-timeline-location-visits); [TechRadar](https://www.techradar.com/computing/software/google-maps-is-about-to-get-a-big-privacy-boost-but-fans-of-timeline-may-lose-their-data)
- In March 2025 Google told affected users it "briefly experienced a technical issue that caused the deletion of Timeline data for some people". Only users who had turned on encrypted backup could restore their data — [GSMArena](https://m.gsmarena.com/google_has_inadvertently_deleted_some_peoples_maps_timelines-news-67095.php); [mjtsai.com](https://mjtsai.com/blog/2025/03/24/google-maps-timeline-data-loss)
- Third-party apps cannot read Timeline. It is a closed, on-device store. (Inference: no public API for on-device Timeline was found. See Gaps.)

**Life360**
- The App Store listing ties history retention to the plan tier: Silver gives 2 days of Location History and Gold gives 30 days. Crash Detection with emergency dispatch and live agent support is in Gold — [Apple App Store: Life360](https://apps.apple.com/us/app/life360-family-safety-gps/id384830320)
- In Australia, Life360's crash detection detects collisions over 40 km/h and contacts the driver. If the driver does not respond, it alerts Circle members. On the paid tier Life360 "will contact emergency responders, sharing precise location coordinates". Life360 claims, unverified, that it dispatched 38,500 ambulances globally in a year — [AAP press release](https://www.aap.com.au/?p=179550)
- Note: Life360's crash detection is built for vehicles (the 40 km/h threshold), not pedestrians. Responders get coordinates, not the history trail — [AAP](https://www.aap.com.au/?p=179550)

**Apple Check In (iOS 17+)**
- Check In has two sharing levels. "Limited" shares current location, battery and network signal for iPhone and Apple Watch. "Full" adds "your route traveled and the location of your last iPhone unlock and Apple Watch removal" — [Apple Personal Safety User Guide](https://support.apple.com/guide/personal-safety/ips56b5bc469/web)
- The contact is sent a link automatically when the phone does not arrive, when the user is significantly delayed and does not respond to the prompt, or after an Emergency SOS call with no follow-up — [Apple Personal Safety User Guide](https://support.apple.com/guide/personal-safety/ips56b5bc469/web)
- Check In data is reported to be end-to-end encrypted — [Gadget Hacks](https://ios.gadgethacks.com/how-to/use-your-iphones-new-check-in-feature-let-contacts-know-when-you-arrive-safely-your-destination-0385415/)
- Apple Crash Detection sends emergency contacts a message with the user's location (not a trail) after a severe crash — [Apple Support: Crash Detection](https://support.apple.com/en-us/104959)

**Google Pixel Safety Check / Car Crash Detection**
- Safety Check runs a timer. If the user does not respond within 60 s when it expires, it starts Emergency Sharing: "Your emergency contacts will receive a text message with a link to track your location via Google Maps." — [Google Store: Pixel safety features](https://store.google.com/magazine/new-pixel-safety-features?hl=en-US)
- Car crash detection notifies emergency contacts and shares real-time location using Google's Emergency Location Service. It uses activity recognition to require that the user is in a car, and it does not trigger on bikes or roller coasters — [Google Store](https://store.google.com/magazine/new-pixel-safety-features?hl=en-US)
- Pixel 10 Satellite SOS can contact emergency services and share location by satellite — [Google Store](https://store.google.com/magazine/new-pixel-safety-features?hl=en-US)

**Garmin Incident Detection / LiveTrack**
- After a detected incident, the watch or app sends an automated message with name, LiveTrack link and GPS location (if available) to up to three emergency contacts. There is a 30 s cancel window. A phone with data is required unless the model has LTE — [Garmin manual](https://www8.garmin.com/manuals/webhelp/GUID-607F08F6-33FC-40BF-9727-84E54043D82D/EN-US/GUID-64F677A5-03E0-453F-BFE3-E1B93D6FA3FC.html); [Garmin blog](https://www.garmin.com/en-SG/blog/running-safety/)
- Garmin Connect does not contact emergency services. Garmin calls the features supplemental — [Garmin blog](https://www.garmin.com/en-SG/blog/running-safety/)
- A LiveTrack link covers an activity session already in progress, so contacts can see the track recorded so far in that activity. Inference from the LiveTrack design; the exact retained window was not verified.

**Noonlight / RapidSOS**
- Noonlight Automatic Crash Response uses GPS, accelerometer and gyroscope. On a detected crash it sends profile data (including medical information) and location to first responders — [VentureBeat](https://venturebeat.com/business/noonlights-updated-app-calls-emergency-services-when-you-suffer-a-car-crash)
- Dispatchers receive coordinates, name and number from Noonlight — [GovTech](https://www.govtech.com/biz/Noonlight-Brings-Caller-Information-to-911-Dispatch-for-Free.html)
- RapidSOS telematics sends "crash data, GPS location, and occupant details" to the right 911 jurisdiction. Fleets get "summaries, timelines" in dashboards. This timeline is for fleet operators, not 911 — [RapidSOS OEM/Connected Vehicles](https://rapidsos.com/enterprise/oem-connected-vehicles/)

**bSafe**
- On SOS, guardians "see and hear everything" through live audio and video streaming plus live location tracking. Audio and video recordings are sent to the guardian. Guardians must install the app — [App Store: bSafe](https://apps.apple.com/us/app/bsafe/id459709106); [Mario Armstrong](https://www.marioarmstrong.com/?p=18481)

### Inferences
- The products split into two groups. **History products** (Timeline, Life360 history, Apple Check In "route traveled") keep a trail but are not wired into crash alerts for responders. **Alert products** (Pixel, Apple Crash Detection, Noonlight, RapidSOS, Garmin) send a point location or a live link. Apple Check In Full is the only one found that ships a past route automatically when the user does not respond.
- What responders receive today is a coordinate, or at best a Garmin or Pixel live-tracking link. A retrospective "last hour with landmarks" view is a differentiator. It is most useful for pedestrians or older people whose last known position is ambiguous (indoors, poor GPS).
- Pedestrian-specific crash detection is uncommon. Life360 (over 40 km/h) and Pixel (in-car activity gating) are built for vehicles. Apple watchOS and Garmin fall detection cover pedestrians, but they share the current location only.

### Gaps
- Samsung Find (formerly SmartThings Find), Strava Beacon and Glympse were not verified this session. From general knowledge, Strava Beacon and Glympse share a live, time-limited link rather than history. Confirm before citing.
- I found no evidence of any public API that lets third-party apps read on-device Google Maps Timeline. This was not confirmed by a primary source.
- I found no current (2026) primary source on whether RapidSOS or NG911 accepts multi-point location history (a trail) from consumer apps, as opposed to a single point.

## 2. Android techniques for battery-efficient continuous location history

### Takeaway
Use `FusedLocationProviderClient` with priority and interval set by activity: BALANCED or LOW_POWER while still or slow, HIGH_ACCURACY only while moving, plus batching via `setMaxUpdateDelayMillis` and passive listening. Store fixes in a Room ring buffer that deletes rows older than about 60–70 min. Fill GPS gaps (indoors, urban canyon) with step-count/heading PDR and barometer floor changes. Google's only published per-hour battery figures are old (2013): about 7.25%/h at HIGH_ACCURACY with 5 s fixes versus about 0.6%/h at BALANCED with 20 s fixes. Measure on current target devices.

### Cited Findings
**Fused Location Provider and batching**
- Android's battery guidance: remove updates when they are not needed. Use `setDurationMillis()` as a safety timeout for fused requests and `setExpirationDuration()` for geofences. Apply batching to all non-foreground uses — [Android Developers: Optimize location for battery](https://developer.android.com/develop/sensors-and-location/location/battery/optimize)
- Batching example: `LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10*60*1000).setMaxUpdateDelayMillis(60*60*1000)` computes a fix about every 10 min and delivers a batch of about 6 roughly every hour, so the app wakes only once an hour. The max delay should be a multiple of the interval — [Android Developers](https://developer.android.com/develop/sensors-and-location/location/battery/optimize)
- Passive location: with `setMinUpdateIntervalMillis(2*60*1000)` the app also receives fixes requested by other apps, at most every 2 min. This "incurs no battery drain" apart from the app's own processing — [Android Developers](https://developer.android.com/develop/sensors-and-location/location/battery/optimize)
- From the docs: BALANCED_POWER_ACCURACY "very rarely uses GPS" and typically uses Wi-Fi and cell. HIGH_ACCURACY is recommended for foreground real-time apps. Background location is throttled to "only a few times an hour" — [Android Developers: Location battery](https://developer.android.com/guide/topics/location/battery)
- Older (Google I/O 2013, Galaxy Nexus) figures: HIGH_ACCURACY with a 5 s interval used about 7.25% battery/hour at about 20 m accuracy. BALANCED with a 20 s interval used about 0.6%/hour at about 40 m. NO_POWER (passive) used about 0 — [PhoneArena](https://www.phonearena.com/news/Googles-new-location-APIs-the-biggest-change-to-location-since-Android-launched_id43153); [The Next Web](https://thenextweb.com/news/inside-googles-new-location-apis-for-android)
- Current Android docs give no mAh or percentage figures — [Android Developers](https://developer.android.com/develop/sensors-and-location/location/battery/optimize)

**Pedestrian dead reckoning (PDR) and floor detection**
- Smartphone PDR suffers from heading drift that can make paths "cross walls". For strapdown INS, error grows with the cube of time. A particle-filter map-matching step and the barometer for vertical displacement help — [Sensors/PMC6832213: Smartphone-Based 3D Indoor Pedestrian Positioning](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6832213/)
- Multi-floor PDR fusing accelerometer, barometer and Wi-Fi with a Viterbi floor detector reached 99.1% (real-time) and 99.7% (batch) floor accuracy. A backtracking particle filter cut median position error by 17% and 13%. Test site: a 7-floor office, 41×27 m floors — [Sensors 2021, PMC8271586](https://pmc.ncbi.nlm.nih.gov/articles/PMC8271586)
- Smartphone barometers show a lag of seconds on height changes, and the lag varies between phones — [Sensors and Materials SM2439](https://sensors.myu-group.co.jp/sm_pdf/SM2439.pdf)

### Inferences (design recommendations; Android API details from general knowledge, check against current docs)
- **Recommended pipeline**
  1. `ActivityRecognitionClient` transitions (STILL, WALKING, IN_VEHICLE) or the `TYPE_SIGNIFICANT_MOTION` trigger sensor gate the sampling rate. While STILL, pause active requests and rely on passive updates and a stored "dwell" point.
  2. While walking, use `PRIORITY_BALANCED_POWER_ACCURACY` at 15–30 s with `setMinUpdateDistanceMeters(~10)`. Escalate to HIGH_ACCURACY at 5–10 s only when the accident model's risk score rises (e.g. near roads, high speed, or the user is in a vehicle).
  3. Add `setMaxUpdateDelayMillis` of 2–5 min so the app processor sleeps between batches. Batched points still carry their original timestamps.
  4. A `ForegroundService` with `foregroundServiceType="location"` gives reliable continuous collection. On Android 14+ the FGS type must be declared, and on Android 10+ background access needs `ACCESS_BACKGROUND_LOCATION`.
- **Ring buffer in Room/SQLite**
  - Table: `(ts INTEGER PK, lat REAL, lon REAL, acc REAL, alt REAL, pressure REAL, speed REAL, bearing REAL, source TEXT{gps|fused|pdr|passive}, activity TEXT, floor INT, wifi_bssid_hash TEXT)`.
  - On each insert, `DELETE WHERE ts < now - 65 min`. At 1 point per 10 s that is at most about 400 rows, which is tiny.
  - Encrypt with SQLCipher, or keep the DB in app-private storage with file-based encryption, and set a Room `autoVacuum` mode.
  - When a crash is detected, freeze the window by copying it to an "incident" table so it is not pruned.
- **PDR fallback**
  - When fused accuracy goes above about 30–50 m, or GPS has had no fix for more than 30 s, start PDR. Use `TYPE_STEP_DETECTOR` (hardware, low power) for steps, a step-length model from height/frequency, and `TYPE_GAME_ROTATION_VECTOR` or `TYPE_ROTATION_VECTOR` for heading.
  - Anchor PDR to the last good fix and re-anchor on the next one. Mark PDR points as low confidence in the UI.
  - Use the barometer delta (about 12 Pa per metre near sea level, general physics knowledge) for floor changes. Account for the lag of seconds noted above.
- **Battery estimate (inference)**
  - An activity-gated BALANCED/passive design sampling every 15–30 s should be near the 0.6%/h order. Always-on HIGH_ACCURACY at 5 s could approach 5–7%/h on older hardware.
  - Modern GNSS chips with duty-cycling and batching are more efficient. Measure with Battery Historian or `dumpsys batterystats` on target devices.
- Geofencing (up to 100 geofences per app per device, general knowledge) works better for "entered or left home or safe zone" landmark events than for trail recording. It adds almost no cost because it piggybacks on fused location.

### Gaps
- No current (2023–2026) authoritative mAh-per-hour measurements for FLP priorities on modern devices were found. Only the 2013 Google I/O figures exist.
- No survey-level PDR accuracy figure (e.g. % error per distance walked for a handheld phone) was retrieved this session. Individual papers use different test conditions, so figures are not comparable.
- Exact OEM background-kill behaviour (Xiaomi, Oppo, Vivo, Samsung "sleeping apps"), which matters a lot in India, was not researched. dontkillmyapp.com is the usual reference.

## 3. Landmark identification (reverse geocoding, POIs, offline maps)

### Takeaway
Do not reverse-geocode every point. Reverse-geocode a few key points (crash point, dwell points, turns) after the incident or lazily. Options:
- Android `Geocoder` (free, needs network, approximate).
- Google Geocoding at $5/1k after 10k free per month.
- Google Places Nearby Search (New) at $32/1k (Pro) after 5k free per month, for named POIs.
- Self-hosted OSM (Nominatim, Overpass, or an offline POI extract) for zero per-call cost.

The public Nominatim and Overpass servers cannot be used for production app traffic. Nominatim allows at most 1 request/s across the whole app, and Overpass suggests about 100 queries/day for regular use.

### Cited Findings
**Google Maps Platform prices** (USD per 1,000 billable events, with free monthly caps) — [Google Maps Platform pricing](https://developers.google.com/maps/billing-and-pricing/pricing)

| SKU | Tier | Free/month | Up to 100K | 100K–500K | 1M–5M | 5M+ |
|---|---|---|---|---|---|---|
| Geocoding (incl. reverse) | Essentials | 10,000 | $5.00 | $4.00 | $1.50 | $0.38 |
| Places Nearby Search (New) | Pro | 5,000 | $32.00 | $25.60 | $9.60 | $2.40 |
| Places Nearby Search (New) | Enterprise | 1,000 | $35.00 | $28.00 | $10.50 | $2.63 |
| Street View Static | Essentials | 10,000 | $7.00 | $5.60 | $2.10 | $0.53 |
| Maps Static | Essentials | 10,000 | $2.00 | $1.60 | $0.60 | $0.15 |
| Dynamic Maps | Essentials | 10,000 | $7.00 | $5.60 | $2.10 | $0.53 |
| Roads (Nearest Road) | Pro | 5,000 | $10.00 | $8.00 | $3.00 | $0.76 |

- **Nominatim (OSMF public server)**
  - Absolute maximum of 1 request/s per application, summed across all users.
  - A valid identifying User-Agent or Referer is required, and the ODbL attribution must be displayed.
  - Systematic queries, such as reverse-geocoding grids, are prohibited. Periodic requests from apps count as bulk and are "strongly discouraged".
  - Apps must be able to turn the service off without an app update.
  - [OSMF Nominatim Usage Policy](https://operations.osmfoundation.org/policies/nominatim/)
- **Overpass API (overpass-api.de)**
  - Under 10,000 queries/day and 1 GB/day roughly avoids disturbing others. For regular use the wiki suggests dividing by 100, i.e. about 100 queries and 10 MB/day.
  - The quota is shared across all users of an app. Back off 30 s on HTTP 429.
  - For heavy or commercial use, self-host, use a paid instance (e.g. Geofabrik, Tracestrack), or filter regional extracts with osmium.
  - [OSM Wiki: Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API)
- **Android `Geocoder`**
  - `Geocoder.isPresent()` checks whether a backend exists.
  - API 33 added the async `getFromLocation(lat, lon, max, GeocodeListener)`, and the synchronous variants are deprecated.
  - It relies on a network backend and results are approximate.
  - Source: [Android Developers: Geocoder](https://developer.android.com/reference/android/location/Geocoder). The fetch returned only the nav portion, so these details come from the fetch tool's general knowledge. Re-verify against the reference page.

### Inferences
- **Cost model**: about 10–15 landmark lookups per incident (crash point plus dwell points and major turns). 1,000 incidents a month is 10–15k lookups, which mostly fits within the Geocoding free cap (10k). Places Nearby at that volume would cost roughly $160–320/month beyond the 5k free.
  - **Why incidents only**: if lookups ran continuously, even every 5 min for 10k users, they would hit millions of calls a month. Only do lookups at incident time or lazily when the user views the trail.
- **Offline-first landmark option**
  - Ship or download a city-level OSM POI extract (amenity, shop, transit stops, named buildings, road names) into a spatial SQLite table (R*Tree), built server-side with osmium or Overpass on a self-hosted instance.
  - Render with MapLibre Native (Android) or osmdroid using offline vector or raster tiles.
  - This works with no network at crash time and costs nothing per call. ODbL attribution is required.
- **Contextual signals that help name a place**
  - Wi-Fi SSID/BSSID of a known place (e.g. "Home Wi-Fi", "Office").
  - Transit station geofences.
  - User-labelled places.
  - Barometric floor ("3rd floor of X Mall").
  - Hash or keep these on-device only.
- **Street View Static** ($7/1k) can be shown in a responder web view for the crash point and the last 1–2 landmarks to help responders recognise the place. Fetch these server-side only when someone opens the link.
- **Roads API Nearest Road / Snap to Roads** can clean the trail for display. It is Pro tier at $10/1k.

### Gaps
- Google Maps Platform Terms limit caching of Geocoding/Places results (usually up to 30 days for lat/lng, with place IDs exempt). This was not verified this session.
- Mapbox and MapLibre offline tile licensing and pricing (Mapbox MAU-based mobile SDK pricing) were not retrieved.
- Google's Geocoding API returns "landmark"-type results (`establishment`, `point_of_interest`), but a detailed comparison with Places Nearby was not fetched.

## 4. Sharing the trail with contacts and responders at crash time

### Takeaway
Use a layered approach, as Pixel and Garmin do:
1. If data is available, upload the frozen last-hour trail to a backend and SMS/push a short, unguessable, expiring HTTPS link to a web map. The page shows the trail, landmarks, accuracy circles and the last known floor.
2. If there is no data, send an offline SMS with the last fix as plain coordinates plus a Google/OSM maps URL, and a compact encoded polyline of a downsampled trail.

Google's encoded polyline format (1e-5 degree precision, delta-encoded, printable ASCII from offset 63) is compact. Some of its characters are outside the basic GSM-7 SMS alphabet, so re-encode before sending by SMS.

### Cited Findings
- Pixel Emergency Sharing sends contacts "a text message with a link to track your location via Google Maps" — [Google Store](https://store.google.com/magazine/new-pixel-safety-features?hl=en-US)
- Garmin sends an automated message with a LiveTrack link plus GPS location to up to three contacts after a 30 s cancel window — [Garmin manual](https://www8.garmin.com/manuals/webhelp/GUID-607F08F6-33FC-40BF-9727-84E54043D82D/EN-US/GUID-64F677A5-03E0-453F-BFE3-E1B93D6FA3FC.html)
- Apple Check In sends a link to the contact, with route traveled at the Full level — [Apple Personal Safety Guide](https://support.apple.com/guide/personal-safety/ips56b5bc469/web)
- Encoded polyline algorithm:
  - Coordinates are rounded to 5 decimals (×1e5).
  - Only the first point is absolute and later points are deltas.
  - Values are zig-zag/two's complement encoded, split into 5-bit chunks with a 0x20 continuation bit, and offset by 63 into printable ASCII. The format is lossy beyond 1e-5°, which is about 1.1 m.
  - Backslashes must be escaped in string literals.
  - [Google: Encoded Polyline Algorithm](https://developers.google.com/maps/documentation/utilities/polylinealgorithm)
- The Roads API, if used for snapping, is $10/1k (Pro) — [Google Maps pricing](https://developers.google.com/maps/billing-and-pricing/pricing)

### Inferences
- **Payload size**
  - Downsample one hour to about 60–120 points: one per 30–60 s, plus every turn, dwell and landmark (Douglas-Peucker with about a 10 m tolerance).
  - Walking deltas between 30 s points are about 0.0003° (30 units), which encode to about 2 characters per coordinate, so about 4–5 characters per point. 100 points is about 500 characters, roughly 4 SMS segments in GSM-7.
  - **Caveat**: the polyline alphabet (ASCII 63–126) includes `` ` `` (not in GSM-7) and `[ \ ] ^ { | } ~` (GSM-7 extension characters that count double). A single backtick forces UCS-2, which allows only 70 characters per segment. Re-map to base64url, or a GSM-7-safe alphabet, before SMS.
- **Offline SMS format suggestion**
  - Line 1: human-readable message, e.g. "Possible accident. Last location: <lat,lon> ±12 m at 14:32, near <landmark>, floor 3".
  - Line 2: `https://maps.google.com/?q=lat,lon`.
  - Line 3: a compact trail token, e.g. `T1:<base64url-polyline>`, that the companion app or web page can decode.
  - Send via `SmsManager.sendMultipartTextMessage`. This needs `SEND_SMS`, which Play restricts to default SMS handlers or approved exceptions. Alternatively, open the SMS intent for user confirmation, which defeats automation when the user is unconscious.
  - Verify the Play SMS/Call Log policy before relying on this (see Gaps).
- **Link security**
  - Use a 128-bit random token in the URL, not coordinates in query strings.
  - Expire links after 24–72 h. Allow the user or an emergency contact to revoke.
  - Serve only the incident window. Log viewers.
- **Responder handoff**: 911/112 PSAPs ingest device location via AML/ELS (single point) and RapidSOS (US). There is no standard trail format, so a URL in the SMS or call note is the practical path. India's 112 (ERSS) accepts SMS and app alerts; integration was not researched.

### Gaps
- The Google Play SMS & Call Log permissions policy (whether a safety app can get `SEND_SMS` as an exception) was not fetched. This is critical for automated offline SMS.
- India ERSS/112 API integration and data formats were not researched.
- No source was found on whether Android's Emergency Location Service exposes anything beyond a point fix.

## 5. Privacy and policy (Play background location, data minimisation, GDPR, India DPDP)

### Takeaway
The feature is a legitimate "physical safety" use of background location under Play policy, but the app must pass the Location Permissions declaration. That requires a ~30 s video, a prominent in-app disclosure before the runtime prompt, and a privacy policy that covers location. Only one background-location feature may be declared. The best privacy posture is a rolling, encrypted, on-device 1-hour buffer that is only sent off-device on an incident or an explicit user action. Google itself moved Timeline in this direction in 2024–25. India's DPDP Rules 2025 core obligations apply from about May 2027, and the rules ban tracking or monitoring of children.

### Cited Findings
**Google Play background location policy**
- Background location is allowed only when "required for the core functionality of the app". It must give significant benefit, and is never allowed only for ads or analytics. Google weighs "physical safety, perceived safety, and health/fitness" as significant benefits — [Play Console Help: Background location](https://support.google.com/googleplay/android-developer/answer/9799150)
- A prominent in-app disclosure is required before the runtime prompt. It must mention "location", say it is used in the background, and list the features. The suggested text is "collects location data to enable [feature] even when the app is closed or not in use" — [Play Console Help](https://support.google.com/googleplay/android-developer/answer/9799150)
- The declaration is made in Play Console > App content > Sensitive app permissions > Location permissions. Only one background-location feature may be declared. A video of about 30 s or less must show the feature, the disclosure and the runtime prompt. A non-PDF privacy policy that covers location is required — [Play Console Help](https://support.google.com/googleplay/android-developer/answer/9799150)
- A foreground service using location must continue a user-initiated in-app action and stop when that action is done. If its use amounts to background location, the background-location rules apply — [Play Console Help](https://support.google.com/googleplay/android-developer/answer/9799150)

**Industry direction**
- Google moved Maps Timeline storage on-device, with a 3-month default auto-delete and optional encrypted backup — [TechRadar](https://www.techradar.com/computing/software/google-maps-is-about-to-get-a-big-privacy-boost-but-fans-of-timeline-may-lose-their-data); [GSMArena](https://m.gsmarena.com/google_has_inadvertently_deleted_some_peoples_maps_timelines-news-67095.php)
- Apple Check In data is reported as end-to-end encrypted — [Gadget Hacks](https://ios.gadgethacks.com/how-to/use-your-iphones-new-check-in-feature-let-contacts-know-when-you-arrive-safely-your-destination-0385415/)

**India DPDP Act 2023 / DPDP Rules 2025**
- The rules were notified 13–14 Nov 2025 with a staggered start:
  - Data Protection Board provisions applied immediately.
  - Consent Manager rules apply after 12 months.
  - Notice, consent, security safeguards, breach reporting, children's data and Significant Data Fiduciary duties apply after 18 months, i.e. about 14 May 2027.
  - [Storyboard18](https://www.storyboard18.com/amp/digital/breaking-govt-notifies-final-dpdp-rules-sets-staggered-rollout-with-key-obligations-for-data-fiduciaries-84201.htm); [DSK Legal](https://dsklegal.com/?p=26455); [EY](https://www.ey.com/content/dam/ey-unified-site/ey-com/en-in/pdf/2025/11/dpdp-act-and-rules.pdf)
- Notices must stand alone and list the itemised personal data, the specific purpose, and how to withdraw consent or complain to the Board — [Mondaq / DSK Legal](https://webiis10.mondaq.com/india/data-protection/1706722/legal-update-digital-personal-data-protection-rules-2025)
- Children (under 18): verifiable parental consent (e.g. via DigiLocker), and no tracking, monitoring, profiling or behavioural targeting of children. Penalties for children's-data violations go up to INR 200 crore — [Seclore DPDP guide](https://www.seclore.com/fundamentals/dpdp-rules-2025-compliance-guide/); [Mondaq](https://webiis10.mondaq.com/india/data-protection/1706722/legal-update-digital-personal-data-protection-rules-2025)
- The sources retrieved did not address location data specifically. It falls under the general notice, consent and purpose-limitation duties — [Vision IAS summary](https://visionias.in/current-affairs/monthly-magazine/2025-12-23/polity-and-governance/digital-personal-data-protection-dpdp-rules-2025)

### Inferences
- **Data minimisation design**
  1. Keep the last 60–65 min only, on-device, in an encrypted DB (SQLCipher or a Keystore-wrapped key), with no cloud sync.
  2. Upload only the frozen incident window, only on a confirmed or uncancelled crash or a user SOS. Delete server copies after the incident closes (e.g. 30 days, configurable).
  3. Store landmarks as derived labels. Do not keep raw Wi-Fi scans or hash BSSIDs.
  4. Let the user view and clear the buffer, and pause the feature, from the app.
  5. Use the 1-hour window as the declared, specific purpose ("help responders find you after a detected accident").
- **GDPR (EU users)**
  - Location is personal data. Consent (Art. 6(1)(a)) or vital interests (Art. 6(1)(d)) applies. Vital interests suits sending data at crash time, but not continuous collection.
  - If health/fall data is combined, Art. 9 special-category rules may apply.
  - A DPIA is advisable for systematic location monitoring.
  - These points are from general GDPR knowledge and were not fetched this session.
- **Elder-care context (the eldering project)**: if a caregiver or child sets up tracking for an elderly person, record consent from the person tracked (or their lawful guardian under DPDP Rule 10 for persons with disabilities). Do not rely only on the setup user.
- **DPDP children's rule**: the ban on "tracking or monitoring" children means a child-safety variant needs careful legal review even with parental consent. Whether safety tracking is exempt was not confirmed.

### Gaps
- The exact GDPR article analysis and EDPB guidance on location data were not retrieved from primary sources this session.
- Whether DPDP Rule 10 exempts safety and emergency tracking of minors (for example via notified exemptions for specific purposes) was not confirmed.
- The Play "Health apps" or "Personal safety" declaration category and the SMS permission exceptions were not checked.
