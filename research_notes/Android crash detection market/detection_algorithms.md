# Smartphone Car-Crash Detection: Sensors, Algorithms, ML, False Positives, Datasets

Research date: 2026-10-12. Primary sources were read in full where possible: the WreckWatch journal PDF, the ICCS 2021 AGH crash-test paper, the Google Research hard-braking paper, the VZCrash arXiv paper, the Apple and Google support pages, and the Android developer docs. Where a fact comes only from a search-result snippet or a secondary outlet, that is noted.

## 1. Which phone sensors matter and how they are used

### Takeaway
The accelerometer is necessary but nowhere near sufficient. Every credible system, academic or commercial, gates on vehicle context (GPS speed, activity recognition, Bluetooth/CarPlay), and then fuses the impact signal with at least one other signal: gyroscope rotation, a speed drop to near zero, loud sound, or (Apple) a barometric pressure spike from airbag deployment. Phone accelerometers commonly saturate at 2g/4g/16g per axis, while real crash forces at the phone can exceed 100g, so raw peak-g thresholds are unreliable on Android.

### Cited Findings
**Accelerometer (impact magnitude)**
- WreckWatch (Vanderbilt, 2011) ignores acceleration events below 4g. It uses a max-acceleration variable φ over a sliding span Sφ, compared with a threshold Mφ. The 4g figure came from drop tests in which a phone dropped from ear height in a car registered "approximately 2G's in each direction with nearly 3G's on the x-axis". A ~25 mph hard stop produced *less* acceleration than the drop, because the deceleration is spread over a longer time. The paper cites 60g as the acceleration required to trigger airbag deployment — [WreckWatch, White et al., MONET 2011](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- WreckWatch's earlier version used acceleration alone and "required higher settings for Mφ" to avoid false positives at low speed. The journal version added acoustics so that it could detect lower-speed collisions — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- WreckWatch notes that car safety systems lengthen the deceleration time, so in low-speed crashes a phone in a pocket or holster may experience less than the Mφ threshold — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- The AGH/PZU crash tests (ICCS 2021) logged phones with per-axis accelerometer limits of **16g, 4g and 2g** across 20 popular phone models, ranging from budget to flagship. They found that "devices with lower limits may experience 'clipping'". Grouping phones by sensor limit and setting per-group thresholds still detected all collisions only "at the cost of a significant number of false positive indications" — [Paciorek et al., ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- In those crash tests, useful crash data spanned 1–3 s, of which **200–400 ms was physical contact** between vehicles — [Paciorek et al., ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- Ordinary driving produced accelerometer values *higher* than a 20 km/h crash. The likely causes were phone use, the phone falling on the floor, and the phone sitting in a door pocket while the door was slammed — [Paciorek et al., ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- Apple's iPhone 14 / Watch Series 8 use a new high-g accelerometer rated to 256g. Apple's Ron Huang said impact forces in these crashes can exceed 100g, and that going higher costs precision at the top of the range and costs power — [TechCrunch, Oct 2022](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/); [AppleInsider](https://appleinsider.com/articles/22/10/10/apple-says-theres-no-single-silver-bullet-behind-crash-detection)
- Apple Watch Series 8 motion sensors are described as able to sample up to 4x faster than predecessors (per search snippet of TechCrunch launch coverage, not verified in full text) — [TechCrunch, Sept 2022](https://techcrunch.com/2022/09/07/apple-watch-series-8-has-new-sensors-that-can-detect-when-youre-in-a-car-crash)

**Gyroscope (rotation/rollover)**
- Apple uses a "high dynamic range gyroscope" to monitor sudden changes in the car's orientation. Apple lists rollovers as a detected crash type — [AppleInsider](https://appleinsider.com/articles/22/10/10/apple-says-theres-no-single-silver-bullet-behind-crash-detection); [Apple Support](https://support.apple.com/en-us/104959)
- In AGH crash tests, a phone gyroscope registered a large angular spike while the vehicle-fixed reference device (±300 deg/s gyro) did not, because an unattached phone rotates more than the car. Ordinary rides also produced gyro spikes, so a gyro threshold alone yielded many false positives — [Paciorek et al., ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- Adding the gyroscope to the decision tree reduced false positives from about 0.6/hour to about 0.5/hour at 80% sensitivity (see section 2) — [Paciorek et al., ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)

**GPS speed (vehicle context, pre-crash speed, sudden stop)**
- WreckWatch only activates detection above a speed threshold Mβ, cited as 15 mph by Thompson et al. and in the ICCS review. It keeps detection active for a span Sβ, or until the phone moves more than Mε feet, after speed drops below the threshold. This covers the stopped-after-crash period while still excluding drops when walking. A known failure mode is that slow traffic can deactivate detection — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf); [ICCS 2021 related-work summary](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- WreckWatch notes that sampling GPS fast enough for accurate speed "reduces the battery life of the device to several hours". It suggested requiring the phone to be plugged into the car, which also serves as in-car context — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- Apple uses GPS to confirm high-speed travel and to detect sudden speed changes. "Depending how fast the traveling speed was earlier, [it] determines what signals we have to see later on" (Huang) — [AppleInsider](https://appleinsider.com/articles/22/10/10/apple-says-theres-no-single-silver-bullet-behind-crash-detection); [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/)
- In Google's hard-braking study, a GPS-speed-based heuristic outperformed an accelerometer heuristic by a large margin: the Transformer model was 3.8x better in PR-AUC than the GPS heuristic, and 166.6x better than the accelerometer heuristic — [Liu et al., Google Research, arXiv 2202.01934](https://arxiv.org/abs/2202.01934)

**Microphone (crash sound / airbag acoustic signature)**
- WreckWatch sets a sound-event threshold Mρ = **140 dB**, citing airbag literature: airbag deployment "can exceed 170dB at peak amplitude", or 160–180 dB. Measured on the phone itself, highway baseline at 55–70 mph was 81 dB, a phone drop was 103 dB, and shouting, laughter, or the radio at max volume with windows down each reached **145 dB**. The authors concluded phone audio clips around 145–150 dB, so sound alone cannot discriminate crashes. They use it as a secondary signal: "145 db and a reading of 3.5G's ... would likely indicate that an accident occurred" — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- Apple's microphone listens for crash sounds, and the system also uses road and engine noise to infer vehicle context. Apple Support says the phone "detects loud sound levels typical of crashes", that raw audio is never collected or stored, and that sensor data is processed on device — [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/); [Apple Support](https://support.apple.com/en-us/104959)
- Google Pixel uses "nearby sounds" and requires microphone permission — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029)

**Barometer (cabin pressure spike from airbag)**
- Apple's barometer detects the cabin pressure change when airbags deploy. Apple compared barometer traces with slow-motion airbag footage in crash labs. **Open windows can make the change too subtle to register.** Barometer performance is similar on iPhone and Watch — [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/)
- Patent literature describes the same technique. One claim covers a mobile device obtaining "a plurality of air pressure measurement signals measuring an air pressure inside the vehicle from a barometer" and tracking the change over time. A separate "Crash detection on mobile device" patent lists inertial sensors, GNSS, barometric pressure change and microphone audio, and describes detecting airbag deployment from pressure plus audio. Assignees were not confirmed; the USPTO PDFs are image-only — [USPTO 11297177](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11297177); [USPTO 12485842](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12485842) (content per search snippets)

**Wi-Fi / Bluetooth / CarPlay / Activity Recognition (vehicle context)**
- Apple uses Bluetooth and CarPlay connections to help determine that the user is in a car, though neither is required. Changes in nearby Wi-Fi routers also indicate travel speed faster than walking or biking — [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/)
- Android's Activity Recognition API (`DetectedActivity`) exposes `IN_VEHICLE` (0), `ON_BICYCLE` (1), `ON_FOOT` (2), `STILL` (3), `UNKNOWN` (4), `TILTING` (5), `WALKING` (7), and `RUNNING` (8). Confidence is 0–100, and a value of 50 or less means another activity may be as likely — [DetectedActivity reference](https://developers.google.com/android/reference/com/google/android/gms/location/DetectedActivity)
- The Activity Recognition API reads short bursts of sensor data periodically, "may stop activity reporting if the device has been still for a while", and uses low-power sensors to resume. The Transition API notifies apps only when the activity changes (for example, entering `IN_VEHICLE`) — [Google Activity Recognition docs](https://developers.google.com/location-context/activity-recognition)
- Pixel crash detection requires location, **physical activity**, and microphone permissions — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029)

**Magnetometer**
- AGH logged magnetometer, gravity and rotation-vector streams alongside accelerometer, gyroscope and GPS. Their final decision-tree features used only the accelerometer and gyroscope — [Paciorek et al., ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)

**Android platform constraints on sensing**
- Apps targeting Android 12 (API 31) or higher are capped at **200 Hz** for the accelerometer, gyroscope and magnetometer via `registerListener()`, and at about 50 Hz (`RATE_NORMAL`) via `SensorDirectChannel`, unless they declare `HIGH_SAMPLING_RATE_SENSORS`. **If the user toggles off microphone access, motion sensors are rate-limited regardless of that permission.** — [Android Sensors Overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- `SENSOR_DELAY_GAME` = 20,000 µs (~50 Hz); `SENSOR_DELAY_FASTEST` = 0. There is no public API for the actual delivered rate, so it must be estimated from timestamps — [Android Sensors Overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- On Android 9 and later, **background apps don't receive continuous-sensor events** (accelerometer, gyroscope). Crash detection must run in a **foreground service** — [Android Sensors Overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- `Sensor.getMaximumRange()` and `getResolution()` expose each device's range. AGH's results show this varies from 2g to 16g — [Android Sensors Overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview); [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- A telematics vendor (Damoov) uses high-frequency buffers of **100 Hz on Android and 60 Hz on iOS**, and captures 5 s before and 5 s after each event — [Damoov on dev.to](https://dev.to/damoov/crash-detection-algorithms-on-smartphones-how-to-minimize-false-positives-in-telematics-apps-4ilh)

### Inferences
- WreckWatch's 4g threshold was derived on a circa-2009 Android phone whose accelerometer probably saturated near 2g. That is likely why its drops read only "2–3g", so the 4g number should not be reused on modern 16g-range phones. AGH found that everyday handling can exceed crash readings, and Damoov describes drops as single-axis, millisecond-scale spikes. Both suggest that drops on modern phones can easily exceed 4g.
- An Android app must read `getMaximumRange()` at runtime and treat readings near full scale as saturated, recording "clipped duration" as a feature rather than trusting the peak. Using features that are less sensitive to clipping, such as velocity change (integrated acceleration), duration above threshold, multi-axis energy, and GPS speed delta, is more robust than peak g.
- Barometer availability on Android is inconsistent (common on flagships, absent on many budget phones), so it should be an optional confirming signal, not a requirement.
- A practical Android context gate is: Activity Recognition `IN_VEHICLE` (confidence over 50), or a car Bluetooth / Android Auto connection, plus GPS speed above roughly 15–25 km/h in the preceding seconds.

### Gaps
- Apple's exact sampling rates, speed thresholds and pressure-delta thresholds are undisclosed.
- I found no published acoustic thresholds or feature definitions for Google's or Apple's microphone pipeline.
- No published barometer delta (in hPa) for airbag deployment in a sealed vs vented cabin was found.
- The exact accelerometer ranges of current Android models (for example, whether any Android phone ships a high-g sensor comparable to Apple's 256g part) were not found in this session.

## 2. Academic work: algorithms, ML classifiers, sampling, saturation, reported accuracy

### Takeaway
Pure threshold detectors produce unacceptable false-alarm rates; AGH measured about 10 false positives per driving hour at 80% sensitivity. Learned models on windowed IMU + speed features cut false positives by 1–2 orders of magnitude: a depth-3 decision tree reached about 0.5–0.6 FP/hour, and CNN-RNN / CNN-Transformer models reached 97.5% AP. Even so, in-the-wild precision drops sharply because real crashes are extremely rare (under 0.02% of harsh events).

### Cited Findings
**WreckWatch (White, Thompson, Turner, Dougherty, Schmidt; Mobile Networks & Applications, 2011)**
- WreckWatch is a formal, threshold-based multi-sensor model. Its variables are max acceleration φ (threshold Mφ, about 4g), a sound event ρ (Mρ = 140 dB, held for Sρ ms), a speed flag β (Mβ mph, held for Sβ), and a distance limit Mε. Two triggers exist: (1) above the speed threshold, acceleration plus sound together exceed the detection threshold MTr; (2) acceleration "alone is so large that it exceeds" the threshold. The user has 10 s to cancel before the alert. The system also records the 30 s route before the crash. — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf); [search summary of WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- An earlier conference version (Thompson et al., 2010) triggered on GPS speed above 15 mph and acceleration above 4g — [ICCS 2021 related-work section](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf); [Springer LNICST 2010](https://link.springer.com/doi/10.1007/978-3-642-17758-3_3)
- WreckWatch reported no quantitative crash-detection accuracy. Its experiments covered drop, hard-stop and acoustic false-positive sources only — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)

**e-NOTIFY / Zaldivar et al. (Univ. Zaragoza group, ~2011)**
- An Android app combined phone accelerometer data with **airbag status read via a Bluetooth OBD-II adapter**. It flagged an accident when the accelerometer exceeded **5g** or the airbag fired, and gave the user 1 minute to cancel before sending a notification by SMS or email — [summarized in Paciorek et al., ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)

**AGH "Effective Car Collision Detection with Mobile Phone Only" (Paciorek et al., ICCS 2021; with PZU insurer and the Institute of Forensic Research)**
- The team ran real crash tests with three cars, including parking-lot bumps, light clashes and a 40.3 km/h head-on collision. Crash speeds ranged from 12 to 43.5 km/h. The reference instrument was a PicDAQ5 (±1.5g and ±200g accelerometers, ±300 deg/s gyro, sampling at 500 Hz, 5 Hz GPS). Negative data came from months of everyday driving, at speeds up to 140 km/h, on 20 phone models — [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- Features (17 for the accelerometer-only tree): raw x, y, z and magnitude; integrals x, y, z and magnitude; average derivatives x, y, z and magnitude; absolute x, y, z; and "accelerometer threshold and frequency". The gyro tree added 17 analogous gyro features. The team trained a depth-8 tree to rank features, then retrained a **depth-3 tree on the top 8 features**, and evaluated it with leave-one-out cross-validation — [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- **Results at 80% sensitivity:** the threshold detector produced about **10 false positives per hour of driving**. The decision tree produced about **0.6/h** with the accelerometer only, and about **0.5/h** with accelerometer plus gyro. Remaining false positives were attributed to low-speed crash signatures resembling fast-driving readings — [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- The authors concluded that "it is not possible to create a credible crash-detection method by using only phone accelerometer readings threshold." They argue false positives should be handled by a "loopback" that contacts the driver first, because false negatives are costlier — [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)

**VZCrash (Verizon Connect, arXiv 2606.06074, 2026): the largest public real-crash IMU benchmark**
- The data comes from dashcam-integrated IMUs on 73,010 US commercial vehicles (2020–2025), **not phones**. It contains 31,090 verified crashes and about 158,000 negatives (84/16 split). Sampling is 100 Hz for the accelerometer and gyro and 1 Hz for GPS speed, in 16 s windows. Hard negatives include potholes, deliberate off-road maneuvers, trailer docking/coupling, near-miss evasive maneuvers, and rough roads — [VZCrash](https://arxiv.org/html/2606.06074v1)
- Test-set AP by model: physical baseline (peak XY acceleration norm, learning-free) **89.62%**; CNN-RNN (1.7M params, 29.4 ms) **97.56%**; CNN-Transformer (1.3M params, 7.2 ms) **97.54%**; Chronos-Bolt (8.8M) 96.64%; MobileNetV3-Small scalogram classifier (2.5M, 57.5 ms) 96.23%; 1D Swin Transformer (189.7M, 215.6 ms) 91.76% — [VZCrash](https://arxiv.org/html/2606.06074v1)
- **In the wild** (735,000 harsh-driving events from about 123,000 vehicles over 48 h; only 143 confirmed crashes, under 0.02%), best-model AP fell from 97.5% to **about 86%**. At 75% recall, the physical baseline produced 780 false positives (12% precision), versus 10 for CNN-RNN trained on the full data (91.5% precision). With only 1–5% of the training data, the deep models fell below the physical baseline — [VZCrash](https://arxiv.org/html/2606.06074v1)

**Google Research, smartphone hard-braking detection (Liu et al., arXiv 2202.01934, 2022)**
- Not crash detection, but the closest published Google pipeline for phone kinematics. Labels came from concurrent vehicle wheel-speed data, collected when phones were connected via Android Auto while navigating in Google Maps. Signals were smoothed with 100 ms averaging and downsampled to **10 Hz**. A sliding window flags a "high acceleration event" when linear acceleration exceeds **5 m/s²**, and a 5 s window around the peak is resampled to 101 points. Transformer, CNN and LSTM encoders were compared; the **Transformer won (PR-AUC 0.83)**. The detected events correlated with public crash records — [Liu et al., arXiv](https://arxiv.org/abs/2202.01934)

**Industry claims (vendor-reported, not peer-reviewed)**
- The Floow claims its smartphone crash detection exceeds 80% in both precision and recall — [The Floow press release](https://www.thefloow.com/latest/the-floow-crash-detection-service-exceeds-80-in-precision-and-recall-and-is-foundational-to-the-floowclaims-product-suite/)
- Cambridge Mobile Telematics says its 2024 "high-frequency crash models" use methods "similar to those used in large language models", but publishes no precision or recall. It validates by crashing cars and comparing against Bosch CDR — [Coverager](https://coverager.com/cambridge-mobile-telematics-announces-enhanced-driver-experiences-crash-capabilities-and-developer-tools/); [CMT blog](https://www.cmtelematics.com/blog/video-crashing-cars-for-accurate-connected-claims-data/)
- Damoov describes a production pipeline of sequential rule gates with configurable profiles (Sensitive / Standard / Tough), three severity bands, and a per-event reliability score. It does not publish thresholds or accuracy figures — [Damoov](https://dev.to/damoov/crash-detection-algorithms-on-smartphones-how-to-minimize-false-positives-in-telematics-apps-4ilh)
- Small student projects (USM Malaysia) report about 80% accuracy with a 4g drop filter, and "90% of false positives removed" over 10 trials. The sample sizes are too small to be meaningful — [USM erepo](https://erepo.usm.my/entities/publication/4b21e2f8-4e91-4e69-99db-4edc8806084f/full) (per search snippet)

### Inferences
- A sensible architecture is a cheap, always-on trigger stage, such as a speed-gated accelerometer magnitude above a few g, or a GPS speed drop, followed by a learned classifier over a several-second multi-sensor window. This mirrors both the Google hard-braking pipeline (heuristic HAE trigger → Transformer) and VZCrash (on-device trigger → classifier).
- The VZCrash results imply that data volume matters more than architecture. Without thousands of real crashes, a well-designed physics/rule baseline may beat a deep model, so an Android app starting with little data should lead with gated rules plus a small tree or GBM, then graduate to a CNN/Transformer as labeled data accrues.
- At realistic base rates, even about 0.5 FP per driving hour (AGH) would be intolerable for auto-dialing emergency services. A user-confirmation countdown is mandatory.

### Gaps
- I could not locate the original e-NOTIFY paper text (Fogue/Zaldivar et al.). Its details come from the ICCS related-work summary.
- No peer-reviewed smartphone-specific CNN/LSTM crash paper with real crash data and phone-drop false-positive rates was found in this session. Most phone-based deep-learning work concerns road anomalies or hard braking.
- The "Crash Detection Using IMU Sensors" (Zhu et al.) F1 0.98 claim appeared only in a search-tool summary and could not be verified.
- No CrashHelp paper was found.

## 3. How Apple and Google describe their approaches

### Takeaway
Apple describes a speed-conditioned sensor-fusion algorithm trained on crash-lab tests plus real-world driving data. It uses a 256g accelerometer, an HDR gyroscope, a barometer, GPS, the microphone, and Wi-Fi/Bluetooth/CarPlay context. Google publicly says only that Pixel uses "location, motion sensors, and nearby sounds", with a 60 s response window. Both run on device and both concede they miss some crashes and false-trigger on some high-impact activities.

### Cited Findings
**Apple**
- Sensors: a 256g accelerometer, HDR gyroscope, barometer (airbag pressure), GPS (speed), microphone (crash sounds, plus road and engine noise for context), Wi-Fi (speed inference), and Bluetooth/CarPlay (in-car context, not required) — [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/)
- "There's no silver bullet, in terms of activating crash detection" (Ron Huang). The algorithm is "pretty dynamic", and the signals required depend on earlier speed. A phone dropped in a moving car should not trigger it because multiple data points are required at once — [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/); [AppleInsider](https://appleinsider.com/articles/22/10/10/apple-says-theres-no-single-silver-bullet-behind-crash-detection)
- Training data: Apple worked with crash labs, placing devices on dummies, the car body and mounts, with interior and exterior high-speed cameras. It used more than "dozens" of crashes with "thousands" of devices, and referenced DOT/NHTSA injury data. Motion algorithms were reportedly trained on "over a million hours of real-world driving and crash record data" (per AppleInsider search snippet) — [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/); [AppleInsider](https://appleinsider.com/articles/22/10/10/apple-says-theres-no-single-silver-bullet-behind-crash-detection)
- Scope: severe front-impact, side-impact, rear-end and rollover crashes in sedans, minivans, SUVs, pickups and other passenger cars. Requires iPhone 14+ (iOS 16+) or Watch Series 8 / SE 2 / Ultra and later. On by default — [Apple Support](https://support.apple.com/en-us/104959)
- Response flow: an alert for **10 s**, then a **30 s countdown** with loud alarms, then an auto-call to emergency services with a looped recorded message. Emergency contacts are notified. Calls fall back to other carriers and then satellite — [Apple Support](https://support.apple.com/en-us/104959); [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/)
- Apple deliberately made the feature hard to trigger. A minor rear-end fender-bender experienced by an Apple VP did not trigger it — [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/)
- Limitations: "cannot detect all car crashes". In an independent test, one missed crash was attributed by Apple to possible "lack of data" — [Apple Support](https://support.apple.com/en-us/104959); [AppleInsider](https://appleinsider.com/articles/22/09/30/iphone-14-wont-always-detect-when-youre-in-a-crash)
- A patent application describes improving accuracy using multiple Apple devices, including AirPods motion sensors (patent application only, not a shipped feature) — [9to5Mac](https://9to5mac.com/2023/09/08/crash-detection-accuracy-airpods/)

**Google (Pixel Personal Safety app)**
- Uses "your phone's location, motion sensors, and nearby sounds". Requires location, physical-activity and microphone permissions. Supported on Pixel 4a and later; the page also has a conflicting "Pixel 3 & later" heading. Needs a SIM; does not work in Airplane mode, with Battery Saver on, or while roaming outside the SIM's country — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029)
- Response flow: vibration, an alarm and a spoken prompt, with a **60 s** window. On no response, the phone goes to speakerphone and calls emergency services with an automated message and approximate location via Android Emergency Location Service. "I'm OK" or "Cancel" stops it, and users can label the event as a minor crash or no crash, which feeds model improvement — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029); [9to5Google](https://9to5google.com/2022/09/07/google-pixel-car-crash-detection/)
- Google acknowledges it "may not detect all crashes" and that high-impact activities may trigger it — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029)
- When a Pixel Watch is paired, both devices check in. If they are not connected, each runs detection independently — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029)
- Google's published research uses a heuristic trigger plus a Transformer on 10 Hz phone kinematics for hard braking (section 2). Google has not published its crash model architecture — [Liu et al.](https://arxiv.org/abs/2202.01934)

### Inferences
- Apple's 10 s + 30 s and Google's 60 s windows bracket the industry norm. WreckWatch used 10 s and Zaldivar used 60 s. A 30–60 s cancel window with escalating alarms is a defensible default.
- Google's in-alert "minor crash / no crash" labeling is a cheap way to build a labeled false-positive corpus, and is worth copying.

### Gaps
- No primary Google engineering description of the Pixel crash model (features, architecture, training data) was found.
- Apple's "million hours" figure was seen only in a search snippet and was not verified on the source page.

## 4. False-positive sources and mitigation

### Takeaway
The main false-positive sources are phone drops or tosses, door slams with the phone in the door pocket, potholes, speed bumps, railway crossings, hard braking, and non-driving high-g activities (roller coasters, skiing). Mitigation layers are: a vehicle-context gate, a pre-impact speed gate, impact duration and multi-axis checks, post-event behavior (speed drops to about zero and stays there), multi-signal agreement (sound or pressure), and finally a user countdown.

### Cited Findings
- **Phone drops / tosses:** Damoov characterizes them as near-instant, single-axis dominant spikes with no prior vehicle speed, and gives magnitudes of 50–200g. *This magnitude conflicts with typical phone accelerometer ranges of 2–16g (AGH) and should be treated as unreliable.* Tosses onto a seat show rotation inconsistent with impact — [Damoov](https://dev.to/damoov/crash-detection-algorithms-on-smartphones-how-to-minimize-false-positives-in-telematics-apps-4ilh); [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- **Door pocket + door slam, phone use, and floor drops** produced readings above real crash readings during ordinary drives — [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- **Potholes / speed bumps:** vertical-dominant spikes of 3–8g while the vehicle keeps moving. **Railway crossings / expansion joints:** rhythmic vertical impulses. **Hard braking:** sustained 3–6g longitudinal deceleration while the vehicle continues. **Aggressive cornering:** lateral g with controlled rotation (vendor figures) — [Damoov](https://dev.to/damoov/crash-detection-algorithms-on-smartphones-how-to-minimize-false-positives-in-telematics-apps-4ilh)
- **VZCrash hard negatives:** potholes, intentional off-road driving, trailer docking/coupling, and near-miss evasive maneuvers — [VZCrash](https://arxiv.org/html/2606.06074v1)
- **Roller coasters and clumsiness:** after the iPhone 14 launch, roller coasters triggered emergency calls, and the WSJ received reports of false positives after phones were dropped or tumbled inside cars — [Jalopnik](https://jalopnik.com/roller-coasters-basic-clumsiness-are-triggering-the-ip-1849637474). Apple shipped "Crash Detection optimizations" in iOS 16.1.2 — [BGR](https://www.bgr.com/tech/apple-releases-ios-16-1-2-with-crash-detection-optimization/)
- **Loud cabin noise** (shouting, laughter, radio at full volume with windows down) reached 145 dB on the phone microphone, the same as the clipping ceiling, so audio cannot be a sole trigger — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- **Mitigation, gate stack (Damoov):**
  1. Velocity gate: typically above 15 km/h in the seconds before the event.
  2. Duration gate: drops last milliseconds, while collision energy lasts hundreds of milliseconds.
  3. Multi-axis gate: significant force on 2 or more axes.
  4. Post-event behavior gate: continuing at highway speed after a spike means reject.
  5. Rotation-correlation gate.

  Damoov also recommends tracking FP per 1,000 trips by road type and recalibrating quarterly — [Damoov](https://dev.to/damoov/crash-detection-algorithms-on-smartphones-how-to-minimize-false-positives-in-telematics-apps-4ilh)
- **Mitigation (WreckWatch):** speed gating above Mβ, an acceleration AND sound conjunction, requiring the phone to be plugged into the car, and a 10 s cancel window — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- **Mitigation (Apple):** speed-conditioned multi-signal requirements, in-car context from Bluetooth/CarPlay/road noise, and a 10 s + 30 s user window — [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/); [Apple Support](https://support.apple.com/en-us/104959)
- **Mitigation (patents):** a patent family states that a sound threshold alone, an acceleration threshold alone, or angular rotation alone may each be rejected, while their combination indicates a crash — [USPTO 9628975](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9628975) (per search snippet). Other patents cross-check against an external, vehicle-mounted accelerometer to reject drops — [search summary of USPTO 7119669](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/7119669)
- **Mitigation (Zaldivar):** OBD-II airbag status is used as an independent confirmation channel — [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)

### Inferences
- For Android, combine: (a) Activity Recognition `IN_VEHICLE`, a car Bluetooth or Android Auto connection, or sustained GPS speed; (b) pre-event speed above about 15–25 km/h; (c) an impact window with multi-axis energy and duration above about 50–100 ms, or saturation persisting across several samples; (d) a GPS speed drop to under about 5 km/h that stays there for N seconds; (e) an optional loud-audio level or barometer step as a confidence booster; (f) a 30–60 s countdown with loud alarm, then auto-SMS or call.
- Roller-coaster and ski false positives are best rejected by the vehicle-context gate, using Activity Recognition and Bluetooth, and by location (geofence known amusement parks or ski resorts).

### Gaps
- No public quantitative breakdown of which false-positive sources dominate in deployed phone apps was found.
- Damoov's g-magnitudes for drops are unverified and appear inconsistent with sensor ranges.

## 5. Public datasets and how to test without crashing a car

### Takeaway
No public dataset pairs smartphone IMU data from real vehicle crashes with phone-drop negatives. The best options are VZCrash (31k real crashes, vehicle-mounted dashcam IMU at 100 Hz), a CC0 Kaggle smartphone dataset with simulated crashes, and fall-detection phone datasets as a proxy for drop negatives. Developers should expect to collect their own negatives (drops, door slams, potholes) and to use crash-test partners, replay, or synthetic augmentation for positives.

### Cited Findings
- **VZCrash** (Verizon Connect, 2026): about 190k samples (31,090 crashes), with accelerometer and gyro at 100 Hz plus 1 Hz GPS speed, in 16 s windows, from dashcam IMUs on commercial vehicles. Hosted at huggingface.co/datasets/vzc-research-chapter/VZCrash. License not stated in the paper — [VZCrash](https://arxiv.org/html/2606.06074v1)
- **Kaggle "Smartphone IMU Road Accident Detection Dataset"**: accelerometer and gyroscope with vehicle speed and GPS; normal driving plus *simulated* crash events; CC0 (per search snippet) — [Kaggle](https://www.kaggle.com/datasets/drabdulbari/smartphone-imu-road-accident-detection-dataset)
- **IEEE DataPort "Smartphone IMU and GPS Dataset"** (IIT Jodhpur): normal travel across modes including car, with no crashes. Usable for driving negatives — [IEEE DataPort](https://ieee-dataport.org/documents/smartphone-imu-and-gps-dataset) (per search snippet)
- **Phone fall-detection datasets** (for example, figshare "Dataset for Mobile Phone Sensing Based Fall Detection") contain labeled phone accelerometer and gyro data for falls and daily activities, a proxy for handling and drop negatives — [figshare](https://figshare.com/articles/dataset/Dataset_for_Mobile_Phone_Sensing_Based_Fall_Detection/1444405) (per search snippet)
- **Phone-drop physics:** arXiv 2406.07834 studies accelerometer recordings of phones dropped onto different floor materials. Data availability was not confirmed — [arXiv 2406.07834](https://arxiv.org/pdf/2406.07834) (per search snippet)
- **SHRP2 Naturalistic Driving Study** and **NHTSA databases** (FARS, NASS, NMVCCS) are crash-causation resources. SHRP2 IMU is 10 Hz, and access is restricted — [Liu et al.](https://arxiv.org/abs/2202.01934); [VZCrash](https://arxiv.org/html/2606.06074v1). WreckWatch reported evaluating against "a publicly available dataset with acceleration readings from real accidents" — [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- **How others obtained crash positives without real-world crashes:**
  - AGH staged driverless crash tests at 12–43.5 km/h with a PicDAQ5 reference logger, using video to time the impacts.
  - Apple worked with crash labs and placed phones on dummies, body and mounts.
  - CMT crashed cars with professionals and compared results with Bosch CDR.
  - Google used Android Auto wheel-speed data as weak labels for braking events.

  Sources: [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf); [TechCrunch](https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/); [CMT blog](https://www.cmtelematics.com/blog/video-crashing-cars-for-accurate-connected-claims-data/); [Liu et al.](https://arxiv.org/abs/2202.01934)
- **Testing false positives:** WreckWatch tested in a moving car with ear-height drops, a hard stop at ~25 mph, and audio sources (shouting, radio, open windows) — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)
- **Demo mode:** Google's Personal Safety app includes a built-in demo of the crash-detection flow — [Android Central / search summary](https://www.androidcentral.com/apps-software/how-enable-car-crash-detection-pixel)

### Inferences
- A practical test plan:
  - **Positives:** (a) replay VZCrash traces, resampled and with the axes re-oriented, through the detection pipeline, with synthetic clipping at 2g/4g/8g/16g to emulate phone saturation; (b) pair with a crash-test facility or insurer, as AGH did with PZU; (c) a sled or pendulum rig with a phone mounted at various positions.
  - **Negatives:** large-scale everyday driving logs, scripted drops, door slams, potholes, speed bumps, and amusement or sports activities.
  - **UX:** a sensor-injection debug mode, similar to Google's demo, for UX and end-to-end alert tests.
- Because VZCrash comes from rigid dashcam mounts, the motion of a loose phone (sliding, rotating, saturating) is missing. Domain adaptation or phone-in-car crash-test data is needed.

### Gaps
- No public crash-test dataset with smartphone sensors was found. NHTSA crash-test instrumentation data exists, but whether it includes phone-like accelerometer traces was not verified in this session.
- CIREN was not investigated (it is a medical/injury case database, unlikely to contain IMU traces).
- Licensing of VZCrash and of the Kaggle dataset was not confirmed on the hosting pages.

## 6. On-device ML feasibility on Android (TFLite / LiteRT)

### Takeaway
Crash classifiers are small: a depth-3 decision tree, or a 1–2.5M-parameter CNN-RNN/Transformer. That is well within on-device budgets, since a quantized 1D-CNN for activity recognition is about 100–360 KB. The real constraints are Android's background-sensor rules (a foreground service is required), the 200 Hz cap without `HIGH_SAMPLING_RATE_SENSORS`, and battery cost from GPS and continuous sampling. A two-stage design (cheap trigger, then model inference on a buffered window) keeps inference rare.

### Cited Findings
- VZCrash model sizes and latencies: CNN-Transformer 1.3M params at 7.2 ms; CNN-RNN 1.7M at 29.4 ms; MobileNetV3-Small scalogram 2.5M at 57.5 ms; the physical baseline 0.5 ms. The hardware for these latencies was not identified in my extraction — [VZCrash](https://arxiv.org/html/2606.06074v1)
- A 1D-CNN HAR model on UCI-HAR is 358 KB in FP32 (46.1 ms) and 113 KB after pruning and INT8 TFLite quantization, with accuracy going from 94.0% to 92.4%. The timing device was not named — [Hugging Face model card](https://huggingface.co/raj5517/imu-activity-classifier/commit/c80ca4e7ecede2ee4a87ecbe70e5a686e480f628)
- TFLite quantization substantially reduces HAR model sizes for mobile without significant accuracy loss (Bursa et al., 2023; per abstract/snippet) — [GSU Avesis listing](https://avesis.gsu.edu.tr/yayin/2b7b580e-ebc0-4ff2-a5e5-b9539d7af643/building-lightweight-deep-learning-models-with-tensorflow-lite-for-human-activity-recognition-on-mobile-devices)
- TFLite Micro runs a 20 KB accelerometer gesture model on microcontrollers — [Zephyr magic_wand sample](https://codex.ac6.fr/Zephyr/raw/Zephyr-latest/samples/modules/tflite-micro/magic_wand/README.rst)
- AGH's final detector is a depth-3 decision tree over 8 features, which is trivially cheap to run — [ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
- Google's hard-braking pipeline downsamples to 10 Hz and only runs the model on windows around heuristic triggers — [Liu et al.](https://arxiv.org/abs/2202.01934)
- Android constraints:
  - the 200 Hz cap (`HIGH_SAMPLING_RATE_SENSORS` needed for more);
  - rate-limiting if the user disables the microphone toggle;
  - no continuous-sensor events in the background (a foreground service is needed);
  - sensors keep drawing power when the screen is off unless unregistered.

  Source: [Android Sensors Overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- The Activity Recognition API is a low-power way to gate high-rate sensing to `IN_VEHICLE` periods — [Activity Recognition docs](https://developers.google.com/location-context/activity-recognition)
- Pixel crash detection is disabled under Battery Saver, which suggests even Google gates it for power — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029)
- GPS sampling at rates needed for speed was a major battery drain in WreckWatch-era phones — [WreckWatch](https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf)

### Inferences
- A suggested Android design:
  - Use the Activity Recognition Transition API to start a foreground service on `IN_VEHICLE`, or on a car Bluetooth / Android Auto connection.
  - Sample the accelerometer and gyro at 100–200 Hz into a ring buffer of about 10–16 s.
  - Get 1 Hz fused location speed.
  - On a cheap trigger (magnitude spike or saturation, or a speed drop), run a quantized TFLite/LiteRT CNN-RNN or CNN-Transformer of 1–2M params (a few MB in INT8) on the window, plus optional audio-level and barometer features.
  - Fuse the result with rule gates and start the countdown.
- Inference cost is negligible because it runs only on triggers. The dominant battery costs are continuous IMU sampling and GPS. Use sensor batching, `maxReportLatencyUs`, where possible, and lower GPS rates when speed is steady.

### Gaps
- No measured TFLite/LiteRT latency for a crash-detection model on a specific Android phone was found. Benchmark with the TFLite benchmark tool on target devices.
- Battery-drain figures (percent per hour) for continuous 100 Hz IMU plus GPS on modern Android phones were not found in this session.
- Android hardware-FIFO batching and wake-up sensor behavior was not covered by the docs page fetched.
