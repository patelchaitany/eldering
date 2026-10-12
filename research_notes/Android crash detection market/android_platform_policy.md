# Android Platform, Play Store Policy, Regulatory and Privacy Constraints for a Third-Party Car Crash Detection App (as of Oct 2026)

Research scope: what a developer must handle to ship an always-on crash detection app on Android 14/15/16 and Google Play. All claims are sourced inline. Items without a source are listed under Gaps.

## 1. Background sensor access: foreground services, Doze/standby, OEM killers, sampling rate, microphone

### Takeaway
An always-on crash detector on Android 9+ can only read the accelerometer continuously from a visible activity or a foreground service (FGS). Since Android 14, that FGS needs a declared type, a matching permission, and a Play Console declaration with a demo video. The realistic choices are `location` (with background location), `health` (via `HIGH_SAMPLING_RATE_SENSORS` or `ACTIVITY_RECOGNITION`), or `specialUse`. Background-start rules mean the service is best started from an Activity Recognition transition, a user action, or boot. OEM battery killers (Samsung, Xiaomi, Huawei, OnePlus) are a separate reliability risk that the platform APIs don't solve.

### Cited Findings
**Background sensor access**
- On Android 9 (API 28) and higher, background apps get no events from continuous-mode sensors (accelerometer, gyroscope) or from on-change and one-shot sensors. Google recommends reading sensors "in the foreground or as part of a foreground service." — [Android Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)

**Sampling-rate cap (Android 12+)**
- For apps targeting Android 12 (API 31) or higher, `registerListener()` motion and position sensor sampling is capped at 200 Hz, and `SensorDirectChannel` is capped at `RATE_NORMAL` (about 50 Hz). Going higher requires declaring `HIGH_SAMPLING_RATE_SENSORS`, otherwise the app gets a `SecurityException`. — [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- If the user turns off the microphone with the device privacy toggle, motion sensors are always rate-limited, even when the app declares `HIGH_SAMPLING_RATE_SENSORS`. — [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)

**Foreground service types (Android 14+)**
- Android 14 (API 34) requires every FGS to declare a type in the manifest, request the matching `FOREGROUND_SERVICE_<TYPE>` permission alongside `FOREGROUND_SERVICE`, and in some cases hold runtime permissions before starting. The `health`, `remoteMessaging`, `shortService`, `specialUse` and `systemExempted` types are new in Android 14. — [FGS types are required (Android 14)](https://developer.android.com/about/versions/14/changes/fgs-types-required); [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- **`health` type:** needs `FOREGROUND_SERVICE_HEALTH`. At least one of these must hold: (a) `HIGH_SAMPLING_RATE_SENSORS` is declared in the manifest, or (b) the app holds one of `BODY_SENSORS`, `READ_HEART_RATE`, `READ_OXYGEN_SATURATION` or `ACTIVITY_RECOGNITION`. Intended use: "long-running use cases to support apps in the fitness category such as exercise trackers." — [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- **`location` type:** needs `FOREGROUND_SERVICE_LOCATION`, location services turned on, and `ACCESS_COARSE_LOCATION` or `ACCESS_FINE_LOCATION`. Intended for "navigation and location sharing." A location FGS can't be created from the background unless the app holds `ACCESS_BACKGROUND_LOCATION`. — [FGS types required (Android 14)](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- **`microphone` type:** needs `FOREGROUND_SERVICE_MICROPHONE` and the `RECORD_AUDIO` runtime permission. — [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- **`specialUse` type:** needs `FOREGROUND_SERVICE_SPECIAL_USE` and has no runtime prerequisite. It covers "valid foreground service use cases that aren't covered by the other foreground service types." The app must add a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="..."/>` free-form justification, which Play reviewers read. — [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- **Play Console declaration (target API 34+):** for each FGS type, the developer must describe the feature, describe the user impact if the system defers or interrupts the task, and link a video showing how the user triggers it. The FGS must be "user perceptible," for example through a clear, accurate ongoing notification. — [Play Console Help: FGS and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
- **Full-screen intents:** on Android 14, `USE_FULL_SCREEN_INTENT` became a special app-access permission. From 22 Jan 2025, only calling and alarm apps get it by default. Other apps, which would include a crash-alert countdown screen, must ask the user. — [Play Console Help 13392821](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)

**Background-start restrictions (Android 12+)**
- Apps targeting Android 12+ can't start an FGS from the background except in listed cases. The cases relevant to a crash app are:
  - "Your app receives an event that's related to geofencing or activity recognition transition"
  - `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`
  - a high-priority FCM message
  - user interaction with a notification or widget
  - Companion Device Manager with `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`
  - "The user turns off battery optimizations for your app"
  - `SYSTEM_ALERT_WINDOW`, which for apps targeting Android 15+ also requires a currently visible overlay window

  — [Restrictions on starting FGS from background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- **While-in-use permissions (Android 14+):** if the app is in the background and starts an FGS of type camera, location or microphone without the matching permission currently in effect, it gets a `SecurityException`. A `health` FGS that relies on `BODY_SENSORS` also fails from the background. The doc notes this "doesn't apply if it's a health service that needs different permissions, like ACTIVITY_RECOGNITION." The exemption list (system component, widget, notification, a PendingIntent from a visible app, and others) does **not** include activity-recognition transitions. — [Restrictions on starting FGS from background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- **Android 15 (target API 35):**
  - `BOOT_COMPLETED` receivers can't launch `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` or `microphone` FGS (the microphone ban dates from Android 14).
  - `dataSync` and `mediaProcessing` FGS get a shared 6-hour limit per 24 hours.
  - The `SYSTEM_ALERT_WINDOW` exemption now needs a visible overlay.
  - Apps must be the top app or run an FGS to request audio focus.

  — [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)
- **Android 16 (all apps):** JobScheduler and WorkManager runtime quotas are now enforced even for active standby buckets, for jobs started while the app was visible, and for jobs running alongside an FGS. `setImportantWhileForeground()` is now ignored. — [Android 16 behavior changes (all apps)](https://developer.android.com/about/versions/16/behavior-changes-all)

**Doze, App Standby and battery-optimization exemption**
- Doze suspends network access, ignores wakelocks, defers standard alarms (allow-while-idle alarms fire at most once per 9 minutes per app), and stops JobScheduler and WorkManager from running. — [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the direct exemption prompt) is only for acceptable use cases. Google's table lists "Safety app (keeps users and families safe)" as **acceptable**. Play policy prohibits requesting a direct exemption "unless the core function of the app is adversely affected." — [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- Google warns: "Don't start a foreground service just to prevent the system from determining that your app is idle." — [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

**OEM battery killers**
- dontkillmyapp.com gives its worst score (5/5) to Huawei, Xiaomi, OnePlus and Samsung ("especially after Android P update"). Meizu, Asus, Oppo, Ulefone/RugOne score 4. Vivo, realme, Motorola, Lenovo, Wiko, Tecno and Blackview score 3. Sony and Unihertz score 2. AOSP/Pixel, Nokia and HTC score 0. The site says these vendors "prefer battery life over proper functionality of your apps" and offers a DontKillMyApp benchmark app for testing. — [dontkillmyapp.com](https://dontkillmyapp.com/)

### Inferences
- **Choosing the FGS type:**
  - `location` is the most defensible type for a driving-only crash detector, because GPS speed and position are needed anyway. It brings the background-location declaration (section 4).
  - `health` with `HIGH_SAMPLING_RATE_SENSORS` or `ACTIVITY_RECOGNITION` avoids that, but the documented intended use is fitness, so Play reviewers may push back on a car-safety app.
  - `specialUse` is the fallback, and it depends entirely on reviewer judgement.
- **Lifecycle that fits the restrictions:**
  1. Register for `IN_VEHICLE` ENTER transitions, which is an explicit background-start exemption.
  2. Start the FGS when a transition arrives.
  3. Stop the FGS on `IN_VEHICLE` EXIT.

  The catch: a `location` FGS started from a transition also needs `ACCESS_BACKGROUND_LOCATION` to actually get location, because while-in-use permissions aren't granted in this path.
- **Microphone:** audio-based crash confirmation, as Pixel uses, is hard for a third-party app. A `microphone` FGS can't start from boot (Android 14+) and generally can't start from the background. It would have to be started from a user-visible state, for example when the user taps "Start drive."
- **Battery exemption:** the "Safety app" row in Google's exemption table is the policy hook for asking users to disable battery optimization. Doing so also unlocks background FGS starts.
- **OEM killers:** expect to ship per-OEM onboarding (auto-start, "unrestricted" battery, lock in recents) on Samsung, Xiaomi and similar phones.

### Gaps
- I found no official statement on whether Play reviewers accept the `health` FGS type for vehicle crash detection. Developer forums only show that `specialUse` gets case-by-case scrutiny ([Play community thread](https://support.google.com/googleplay/android-developer/thread/240847726/special-use-foreground-service-type?hl=en), Product Expert Alumni answer, not an official policy).
- I didn't verify current Android 16 changes to health permissions (for example, `BODY_SENSORS` moving to granular Health Connect permissions). The Android 16 "all apps" page doesn't mention them, and I didn't fetch the "targeting Android 16" page.
- I didn't retrieve primary docs on sensor batching, FIFO, `maxReportLatencyUs` or wake-up sensors. The sensors overview doesn't cover them; see the `SensorManager.registerListener(..., maxReportLatencyUs)` API reference.
- I didn't fetch dontkillmyapp's per-vendor workaround pages.

## 2. Detecting driving to save battery: Activity Recognition Transition API, Bluetooth and Android Auto triggers

### Takeaway
The Activity Recognition Transition API (Google Play services) gives `IN_VEHICLE` ENTER and EXIT events through a PendingIntent. It needs the `ACTIVITY_RECOGNITION` runtime permission on Android 10+, and its events are an explicit exemption for starting an FGS from the background. That makes it the natural trigger for gating high-rate sensing. Its latency varies by device, so the first minutes of a drive may go unmonitored.

### Cited Findings
- The Transition API supports `IN_VEHICLE`, `ON_BICYCLE`, `RUNNING`, `STILL` and `WALKING`, each with `ACTIVITY_TRANSITION_ENTER` or `ACTIVITY_TRANSITION_EXIT`. Apps register with `requestActivityTransitionUpdates(request, pendingIntent)`, and events arrive in a BroadcastReceiver in chronological order. "The latency of event detection might vary by device." — [Activity Recognition Transition API](https://developer.android.com/develop/sensors-and-location/location/transitions)
- Android 10 added the `android.permission.ACTIVITY_RECOGNITION` runtime permission for apps that "classify the user's physical activity, such as walking, biking, or moving in a vehicle." The Activity Recognition API "don't provide results unless the user has granted your app this permission." — [Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes)
- Receiving an activity-recognition transition event is a listed exemption for starting an FGS from the background (Android 12+). — [FGS background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- Companion Device Manager apps with `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` can also start an FGS from the background. This is a possible route for a paired dedicated device such as an OBD dongle or car-mounted tag. — [FGS background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- Doze's own guidance names apps that only connect to "standard Bluetooth devices such as wireless headphones" as not acceptable for a battery exemption on that basis alone. — [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- Commercial telematics SDKs describe the same gating pattern. DriveKit says the app "doesn't drain the phone's battery except when the policyholder is driving." — [DriveQuant DriveKit](https://www.drivequant.com/crash-detection-sdk-telematics-drivekit)

### Inferences
- **Recommended pipeline:**
  1. Activity Recognition `IN_VEHICLE` ENTER (low-power, Play services) starts the FGS.
  2. The FGS runs the accelerometer, ideally batched, plus duty-cycled GPS.
  3. `IN_VEHICLE` EXIT, or a long `STILL` period, stops the FGS.
- **Faster start triggers:** a car Bluetooth connection (`ACL_CONNECTED` to a user-tagged car head unit) or Android Auto connection can start drives sooner. These broadcasts are **not** in the documented FGS background-start exemption list, unless the app uses Companion Device Manager or the user has turned off battery optimization.
- Because detection latency varies by device, a hybrid of Activity Recognition, Bluetooth, and a speed check from a geofence exit improves coverage.

### Gaps
- I didn't retrieve primary documentation on using Android Auto or car Bluetooth connection broadcasts as drive triggers, or on how those broadcasts interact with Android 14–16 implicit-broadcast limits.
- I found no published accuracy or latency benchmarks for `IN_VEHICLE` detection.

## 3. Emergency calling and SMS: can a third-party app auto-dial 911/112? SMS/Call Log policy, alternatives

### Takeaway
A third-party Android app **can't** programmatically place a call to an emergency number. `ACTION_CALL` is blocked for emergency numbers, and only `ACTION_DIAL` works, which needs the user to press Call. Pixel's built-in Car Crash Detection auto-calls only because it is a privileged system feature. The app can auto-send SMS to personal emergency contacts if it gets the Play SMS exception "Physical safety/emergency alerts to send SMS" (`SEND_SMS` only). Server-side SMS (Twilio and similar) and push notifications avoid the Play permission entirely but bring carrier registration (US 10DLC, India DLT) and need data connectivity.

### Cited Findings
**Calling**
- `Intent.ACTION_CALL` docs: "Note: this Intent cannot be used to call emergency numbers. Applications can dial emergency numbers using ACTION_DIAL, however." They also say "there will be restrictions on which applications can initiate a call; most applications should use the ACTION_DIAL," and that targeting M+ without `CALL_PHONE` granted throws a `SecurityException`. — [Android Intent reference: ACTION_CALL](https://developer.android.com/reference/android/content/Intent#ACTION_CALL)
- Play's alternative for starting calls: the Dial Intent "can specify a phone number and open the phone app. The user can then explicitly initiate the phone call. The Dial Intent doesn't require the CALL_PHONE permission." — [Play Console Help: SMS/Call Log permissions](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)
- The `phoneCall` FGS type requires `MANAGE_OWN_CALLS` or holding the `ROLE_DIALER` default-dialer role. — [FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- **Benchmark: Pixel Car Crash Detection.**
  - It uses location, motion sensors and "nearby sounds," and needs location, physical activity and microphone permissions.
  - After a crash it gives the user 60 seconds to respond, then auto-calls emergency services on speakerphone, plays a crash message, and shares approximate location.
  - It needs a SIM, doesn't work in Airplane mode or Battery Saver, works only in the SIM's country (not while roaming), and supports 20 countries.
  - Google states: "Your phone may not be able to detect all crashes."

  — [Pixel Phone Help: Car Crash Detection](https://support.google.com/pixelphone/answer/7055029?hl=en)

**SMS and Call Log policy**
- Google Play restricts the SMS and Call Log permission groups to default SMS, Phone or Assistant handlers, plus listed exceptions. Apps must file a Permissions Declaration Form, and apps that don't qualify must remove the permissions. Apps that fail may be removed from Play, and "deceptive and non-declared uses" may lead to developer account termination. — [Play Console Help 10208820](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)
- Exceptions relevant to a crash app (verbatim rows):
  - "Physical safety/emergency alerts to send SMS — Apps that send SMS alerts in emergency situations" allows **`SEND_SMS`**.
  - "In-vehicle hands-free use and projected display — Apps whose core functionality (like navigation), directly relates to driving/mobility" allows `RECEIVE_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, `WRITE_SMS`, `PROCESS_OUTGOING_CALLS`, `WRITE_CALL_LOG` and `READ_CALL_LOG`.
  - "Device automation" allows `SEND_SMS` and others.

  Exceptions are granted only if "there's currently no alternative method to provide the core functionality." — [Play Console Help 10208820](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)
- Invalid use cases include "Family or device locator" and "Remote control of user phone or other devices." The suggested alternative for sending a text is the SMS Intent, which opens the messaging app for the user to send. — [Play Console Help 10208820](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)

**Server-side SMS alternatives**
- **US (Twilio):** every A2P SMS to US numbers must run through a registered 10DLC campaign (brand, campaign, numbers), or "texts will be blocked." Registration carries one-time and monthly carrier fees. Toll-free numbers follow a separate verification process. — [Twilio: A2P 10DLC registration](https://support.twilio.com/hc/en-us/articles/13718729624091-Required-information-for-United-States-A2P-10DLC-registration); [Twilio blog: new 10DLC requirements](https://www.twilio.com/en-us/blog/new-requirements-for-a2p-10dlc-registrations)
- **India:** local-route SMS needs TRAI DLT registration of the entity, sender header and exact message templates. Network-level scrubbing blocks non-matching content, and only India-registered entities can register. — [AWS End User Messaging: India sender ID registration](https://docs.aws.amazon.com/sms-voice/latest/userguide/registrations-sms-senderid-india-register.html); [Telerivet: India SMS compliance](https://www.telerivet.com/blog/india-sms-compliance-trai-dlt-registration-and-tcccpr-guide)

### Inferences
- **Practical design:**
  1. On a detected crash, show a full-screen countdown (needs the user-granted `USE_FULL_SCREEN_INTENT` special access).
  2. If the user doesn't respond, auto-send on-device SMS to user-chosen contacts. This requires the "Physical safety/emergency alerts" `SEND_SMS` declaration. On-device SMS works without mobile data but needs cellular service.
  3. In parallel, send a server-side alert (push or Twilio SMS/voice) with the location link.
  4. Pre-fill the dialer with 911/112 via `ACTION_DIAL` so a conscious user can call with one tap.
- **Third-party monitoring center:** fully automatic 911/112 contact would need a center (like OnStar or EU TPS eCall) that receives the alert and calls the PSAP with a live agent. The app itself can't auto-dial.
- WhatsApp has no officially sanctioned way for a third-party Android app to send messages silently in the background. The WhatsApp Business API sends from a business number, not the user's account. This is an inference, not researched in depth.

### Gaps
- I didn't find Play's published review criteria or turnaround times for the "Physical safety/emergency alerts" SMS exception, or examples of approved crash-detection apps.
- I didn't verify whether US carriers or Twilio have an emergency-alert campaign type that eases 10DLC vetting.
- I didn't research the WhatsApp Business Platform's terms for safety alerts.

## 4. Location permission policy (ACCESS_BACKGROUND_LOCATION) and Play review

### Takeaway
Background location is allowed on Play when it gives a "significant benefit." Physical safety qualifies explicitly. The app must pass a declaration with a video of 30 seconds or less and show a prominent in-app disclosure before the runtime prompt. A `location`-type FGS started from a visible activity counts as foreground access, so background location is technically avoidable if every drive starts from user action. Automatic drive detection effectively requires it.

### Cited Findings
- Android 10 introduced `ACCESS_BACKGROUND_LOCATION`. An app is treated as accessing location in the background unless an activity is visible or it runs an FGS of type `location`. Geofencing on Android 10+ also requires `ACCESS_BACKGROUND_LOCATION`. — [Android 10 privacy changes](https://developer.android.com/about/versions/10/privacy/changes)
- An app in the background can't create a `location` FGS that gets location unless it holds `ACCESS_BACKGROUND_LOCATION`. Under the documented exemptions (notification, widget and others), an app with background location can access location all the time. — [FGS types required](https://developer.android.com/about/versions/14/changes/fgs-types-required); [FGS background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- **Play policy:**
  - Background location must be "required for the core functionality" and provide "a significant benefit." Physical safety, perceived safety and health/fitness count as significant. Ads, analytics, personalization and convenience don't.
  - The declaration covers one feature and needs a video of 30 seconds or less showing the background trigger, the prominent disclosure and the runtime prompt.
  - The disclosure must come before the permission prompt, must use the word "location," must say it runs "when the app is closed / not in use," and can't live only in the privacy policy.

  — [Play Console Help: Background location access](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en)

### Inferences
- Crash detection fits squarely in "physical safety," which gives a strong basis for approval. The video must show the auto-start behavior while the app is closed.
- Expect the review to check that the declared feature matches the store listing and data-safety form. Any secondary use of location (insurance scoring, ads) would weaken the case and must be disclosed.

### Gaps
- The Play help article doesn't state review timelines. I found no official data on rejection rates for safety apps.

## 5. Regulatory: EU eCall, FCC/911 rules, liability, medical device status, GDPR/CCPA/DPDP

### Takeaway
Regulators don't require phone-based crash apps to meet eCall rules, but those rules set user expectations and make phone apps a secondary layer in new EU cars. In the US, the TCPA bars autodialed or prerecorded calls to 911 lines except for "emergency purposes," and a separate PSAP Do-Not-Call registry carries heavy per-call fines. Together these favor live-agent relay over robocalling PSAPs. On privacy, location data and post-crash audio are high-sensitivity data. The EDPB, GDPR and India DPDP (substantive obligations from May 2027) all point to consent, minimisation and short retention.

### Cited Findings
**EU eCall**
- Regulation (EU) 2015/758 requires 112-based eCall in all new M1/N1 vehicle types from 31 March 2018. It also allows owners to use a third-party-service (TPS) eCall alongside it. — [Regulation (EU) 2015/758, legislation.gov.uk](https://www.legislation.gov.uk/eur/2015/758/introduction/data.html); [EP Legislative Observatory summary](https://oeil.europarl.europa.eu/oeil/en/document-summary?id=1390027)
- Delegated Regulation (EU) 2024/1084 introduced NG eCall over packet-switched (4G/5G) networks. New PSAP systems must support NG eCall from 2 May 2024, and all existing PSAPs by 1 January 2026. — [EENA eCall page](https://eena.org/our-work/eena-special-focus/ecall/); [Council doc ST-7055-2024](https://data.consilium.europa.eu/doc/document/ST-7055-2024-INIT/en/pdf). The 2027 requirements for in-vehicle systems after 2G/3G shutdown come from the Commission's 2024 explanatory memorandum, per the same Council doc.
- In TPS eCall, a third-party centre (often the OEM's) receives the call first and passes it to the PSAP. — [CEPT ECC draft Recommendation (17)04](https://cept.org/files/2099/Draft%20revision%20of%20ECC%20Recommendation%20(17)04.docx)
- A blog reports that Delegated Regulation (EU) 2025/1871 updated standards references (EN 17184:2024, EN 17240:2024), mandatory for new type approvals from 1 Jan 2027. — [3G4G blog, Aug 2026](https://blog.3g4g.co.uk/2026/08/?hl=en). **Unverified against EUR-Lex.**

**US 911 / FCC**
- 47 U.S.C. § 227(b)(1)(A) (TCPA) makes it unlawful to call "any emergency telephone line (including any 911 line…)" using an automatic telephone dialing system or an artificial or prerecorded voice, "other than a call made for emergency purposes or made with the prior express consent of the called party." Private damages are $500 per violation, trebled if willful. — [TCPA overview, Wikipedia](https://en.wikipedia.org/wiki/Telephone_Consumer_Protection_Act_of_1991); [47 USC 227 text (OpenJurist)](https://m.openjurist.org/47/usc/227)
- Section 6507 of the Middle Class Tax Relief and Job Creation Act of 2012 required an FCC PSAP Do-Not-Call registry. It bars automatic dialing equipment from calling registered PSAP numbers, with penalties of $10,000–$100,000 per call, and $100,000–$1M per incident for disclosing registry numbers. The FCC implemented it in Oct 2012 at 47 CFR § 64.1202, and it applies to any entity that delivers calls or texts using autodialers. — [FCC: rules to protect emergency service providers from robocalls](https://www.fcc.gov/document/fcc-adopts-rules-protect-emergency-service-providers-robocalls); [Federal Register 78 FR 18245 (Mar 26 2013)](https://www.gpo.gov/fdsys/pkg/FR-2013-03-26/html/2013-06838.htm); [DWT summary](https://dwt.com/insights/2012/10/fcc-adopts-rules-requiring-all-users-of-autodialin)
- NENA publishes "Public Safety Considerations for Smartphone App Developers." The search snippet says developers "should clearly advertise whether the app will dial 9-1-1 directly or whether it will use another method." — [NENA smartphone apps page](https://www.nena.org/page/smartphoneapps). Only the snippet was available; the full page returned HTTP 403.

**Privacy (EU/EDPB)**
- EDPB Guidelines 01/2020 on connected vehicles were adopted as v2.0 on 9 Mar 2021. They flag geolocation as needing special attention and stress data minimisation, early deletion and anonymisation. Per a law-firm summary, eCall data should go only to emergency services and service partners in a serious accident, and be kept only as long as the emergency needs it. Consent is likely the main legal basis under ePrivacy Art. 5(3). — [EDPB Guidelines 01/2020 PDF](https://edpb.europa.eu:443/sites/default/files/consultation/edpb_guidelines_202001_connectedvehicles.pdf); [CMS summary](https://cms.law/en/aut/legal-updates/EDPB-issues-guidelines-on-Connected-Cars); [Taylor Wessing commentary](https://www.taylorwessing.com/de/interface/2021/autotech-and-mobility/edpb-guidelines-on-connected-vehicles-as-of-march-2021)

**India DPDP**
- The DPDP Rules 2025 were notified in mid-November 2025 (sources differ on 13 vs 14 Nov) and phase in:
  - Board and institutional provisions immediately.
  - Consent manager rules about 12 months later (around 13–14 Nov 2026).
  - Substantive obligations (notice, consent, security safeguards, breach reporting, data principal rights, retention and erasure, children's data) from about 13–14 May 2027.

  The Act's penalty schedule goes up to INR 250 crore for failing to keep reasonable security safeguards. — [Uniqus: DPDP timelines](https://uniqus.com/digital-personal-data-protection-act-timelines/); [Mondaq: DPDP Rules 2025 legal update](https://webiis10.mondaq.com/india/data-protection/1706722/legal-update-digital-personal-data-protection-rules-2025); [Anantam IAS, 14 Nov 2025](https://anantamias.com/current-affairs/dpdp-rules-2025-notified/?pdf=1)

**Disclaimers (industry practice)**
- Pixel's own help page says "Your phone may not be able to detect all crashes" and that high-impact activities can trigger false alerts. This sets an industry precedent for detection disclaimers. — [Pixel Car Crash Detection](https://support.google.com/pixelphone/answer/7055029?hl=en)

### Inferences
- **US calling design:** an app or backend that auto-calls 911 with a prerecorded message would rely on the TCPA's "emergency purposes" carve-out. It would also have to make sure it never autodials numbers on the PSAP Do-Not-Call registry, which mostly covers 10-digit PSAP admin lines. The lower-risk design is a live-agent monitoring center (the OnStar/TPS model), or notifying personal contacts who then call 911.
- **EU market:** new cars since 2018 already have 112 eCall, so an EU phone app is mainly valuable for older vehicles, motorcycles, bicycles and passengers in non-eCall vehicles.
- **Medical device status:** crash detection that alerts contacts, without diagnosing or treating injury, is unlikely to be a medical device under EU MDR or FDA rules. Adding injury-severity triage or vital-sign inference could change that. This is not verified.
- **Data handling:** crash-time audio and continuous location are the riskiest data. Defensible practice is:
  - process sensors on-device;
  - upload only on a confirmed crash event;
  - keep audio and trace data briefly;
  - get explicit consent for any secondary use such as insurance scoring.

  Injury information, if collected, could be GDPR Art. 9 health data (inference).
- **Liability:** terms of service should say the app is not a substitute for emergency services, may miss crashes or false-alarm, and depends on connectivity, battery and OEM background limits. This is an inference drawn from Pixel's disclaimers.

### Gaps
- I couldn't retrieve NENA's full guidance (HTTP 403) or any FCC rule specific to apps that auto-dial 911.
- I didn't verify US state laws on automated or prerecorded calls to 911.
- I found no primary-source medical device classification ruling (FDA or EU MDR/MDCG) for crash detection apps.
- I didn't research CCPA/CPRA "precise geolocation" sensitive-personal-information rules (opt-out of use beyond necessary, notice at collection) in this pass.
- I couldn't confirm via EUR-Lex any 2026 TPS-eCall-specific delegated act. The 2025/1871 reference is unverified.

## 6. Battery impact benchmarks for continuous accelerometer/GPS monitoring

### Takeaway
There is no rigorous public per-hour benchmark for crash detection apps. The available data says the accelerometer alone costs about 3x idle drain while GPS costs about 9–13x. Gating GPS with motion or activity detection is the main saving. The only commercial per-hour figure is a vendor claim of about 2% per hour of active driving.

### Cited Findings
- A thick-client smartphone journey-detection study (arXiv 1908.10725) found the accelerometer raised discharge to about 3x the idle rate, while GPS raised it 9–13x. — [arXiv 1908.10725](https://arxiv.org/pdf/1908.10725)
- In the TEXIVE study on a Galaxy S3, battery fell 2% in 30 minutes without inertial sensors, 9% with continuous inertial sensing, and 4% with a reduced sensing rate. The authors note GPS energy is "much larger than sensors" (84% to 70% over the same test). — [TEXIVE, arXiv 1307.1756](https://arxiv.org/pdf/1307.1756)
- Adaptive GPS sampling driven by accelerometer data cut GPS sensing by 78% while keeping 91.4% accuracy. — [KFUPM: energy-efficient adaptive GPS sampling](https://pure.kfupm.edu.sa/en/publications/energy-efficient-adaptive-gps-sampling-using-accelerometer-data/)
- Greenlight (vendor claim) says its crash detection drains about 2% per hour of active driving unless the phone is plugged in. — [Greenlight Help](https://help.greenlight.com/hc/en-us/articles/9323051701147-What-is-the-impact-of-crash-detection-on-my-phone-s-battery)
- DriveQuant (vendor claim) says DriveKit doesn't drain the battery except while driving, when GPS is the main cost. — [DriveQuant](https://www.drivequant.com/crash-detection-sdk-telematics-drivekit)
- Pixel Car Crash Detection is disabled in Battery Saver mode. — [Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)

### Inferences
- The battery budget is driven by GPS duty cycle and by the time the high-rate accelerometer runs outside drives. Activity Recognition gating plus batched accelerometer reads keep the always-on cost close to the Play-services baseline.
- Older studies (Galaxy S3 era) overstate absolute drain on 2026 hardware with low-power sensor hubs.
- Many users drive with the phone charging, which hides drive-time cost but doesn't help with forgotten FGS sessions.

### Gaps
- I found no independent, recent (2023–2026) measurement of per-hour drain for crash detection apps or for 100–400 Hz accelerometer streaming on modern sensor hubs.
- I found no Google-published power figures for the Activity Recognition API.
