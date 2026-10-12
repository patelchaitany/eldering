# Competitor Landscape: Smartphone and In-Vehicle Car Crash Detection Products (as of Oct 2026)

Research notes for the "Android crash detection market" report. Scope: what each product offers, the emergency flow, platform, pricing/business model, and regional availability. Research done 2026-10-12. Several sources are from 2022-2025. Dates are noted where known. Firecrawl search was rate-limited partway through, so later lookups used WebSearch result summaries. Those are flagged where I could not open the primary page.

---

## 1. Google Pixel Car Crash Detection (Personal Safety app) and Android-wide equivalents

### Takeaway
Google's Car Crash Detection is the reference Android implementation. It is free, works only on Pixel 4a and later phones and on Pixel Watch paired with a Pixel phone, and covers about 20-21 countries and 12 languages. It is opt-in and needs several permissions. The flow is a 60-second spoken and on-screen prompt, then an automatic speakerphone call to emergency services that plays a recorded crash message with location, and optional Emergency Sharing to contacts. No other Android OEM had a confirmed live equivalent outside China as of 2026. Samsung phones carry a crash sensor in firmware but the feature is not enabled. Xiaomi launched one in China in mid-2025.

### Cited Findings
**Supported devices and setup**
- Car Crash Detection works on Pixel 4a and later, including Fold. It also works between Pixel Watch and Pixel phone when paired over Bluetooth. — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)
- Requirements: a SIM card (it does not work when roaming or in Airplane mode), Location permission, Physical Activity permission and Microphone permission. Battery Saver must be off. It only works in the country of the phone's SIM. — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)
- The feature is not on by default and needs a more involved setup with location and other privacy permissions (contrast with Apple, where it is on by default). — [Gizchina summary via search](https://www.gizchina.com/2025/07/16/xiaomi-just-taught-your-phone-to-call-for-help-after-a-crash/); [Data Doctors/WTOP](https://wtop.com/tech/2024/08/column-how-to-enable-crash-detection)
- The Personal Safety app is free on Google Play (package com.google.android.apps.safetyhub), rated 4.0 from about 262K ratings. The listing says it is "Not available for all countries, languages, and devices." — [Google Play](https://play.google.com/store/apps/details?id=com.google.android.apps.safetyhub)

**Emergency flow**
- After a crash is detected, the phone vibrates, sounds an alarm and asks "do you need help" both aloud (on speakerphone) and on screen. The user has **60 seconds** to respond. — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)
- To cancel, the user says "Cancel" or taps "I'm OK". To escalate, the user says "Emergency" or drags the slider to "Call 911 & notify contacts" (when Emergency Sharing is on). — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)
- If there is no response, the phone calls emergency services on speakerphone, plays a message saying a car crash happened, and gives the device's approximate location. The message repeats, and the user can talk over it or tap Cancel to stop the message and stay on the call. The call uses Android Emergency Location Service (ELS). — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)
- Emergency Sharing can text contacts the user's real-time location (a Google Maps link), battery level and updates. It ends after 24 hours. — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)
- When a Pixel Watch and Pixel phone are both paired, both check in with the user, who can respond on either device. — [Pixel Watch Help](https://support.google.com/googlepixelwatch/answer/12663810)
- Google's limitations: "may not be able to detect all crashes", high-impact activities may trigger it, and it may fail on weak networks or during an ongoing call. — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en); [Made by Google on Facebook](https://www.facebook.com/madebygoogle/videos/pixel8-pixel-8-pro-safety-features/733406562336758/)
- Related features: Safety Check (a timed check-in from 15 minutes to 24 hours that starts Emergency Sharing if missed) and Satellite SOS (Pixel 9 series, US only except Hawaii and Alaska, triggered manually from the dialer). The help page does not describe Satellite SOS as part of the automatic crash-call flow. — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)

**Countries and languages**
- Countries: Australia, Austria, Belgium, Canada, Denmark, France, Germany, India, Ireland, Italy, Japan, Netherlands, Norway, Portugal, Singapore, Spain, Sweden, Switzerland, Taiwan, UK and US (21). Languages: Danish, Dutch, English, French, French (Canada), German, Italian, Japanese, Mandarin Chinese, Norwegian, Spanish and Swedish. — [Google Pixel Help](https://support.google.com/pixelphone/answer/7055029?hl=en)
- The Pixel Watch help page lists 20 countries and omits Germany. A cached version includes Germany, so the list may be in flux. — [Pixel Watch Help](https://support.google.com/googlepixelwatch/answer/12663810)
- Expansion to Austria, Belgium, India, Portugal and Switzerland was announced around 31 October 2023. Hindi is not a supported language despite India support. — [9to5Google](https://9to5google.com/2023/10/31/google-pixel-car-crash-detection-new-countries/); [Android Central](https://androidcentral.com/phones/pixel-car-crash-detection-expands-to-more-countries)
- The Canada launch was announced as a Pixel Feature Drop. At that time the feature was already in the US, Taiwan, Italy, France, Spain, Ireland, Japan, UK, Australia and Singapore (exact date not shown in the snippet). — [Made by Google on Facebook](https://www.facebook.com/madebygoogle/posts/your-pixel-can-now-call-for-help-in-canada-with-car-crash-detection-when-the-fea/2258886650956684/)
- A November 2025 Personal Safety release note on APKMirror (third party) repeats the AT/BE/PT/CH/IN expansion and says Fall Detection, Emergency SOS, Emergency Sharing and Safety Check expanded on Pixel Watch. — [APKMirror](https://www.apkmirror.com/apk/google-inc/personal-safety/personal-safety-2025-11-13-831777920-0-release_gms-release)

**Android-wide and Samsung equivalents**
- **Samsung (phones):** Samsung's Galaxy S24 and S25 firmware contains a "car crash detection" virtual sensor, but the feature was not live in any region. — [Android Authority](https://www.androidauthority.com/samsung-galaxy-s25-car-crash-detection-sensor-3511840/); [Android Police](https://www.androidpolice.com/samsung-galaxy-s25-ultra-may-finally-life-saving-pixel-feature/)
- **Galaxy S26:** A February 26, 2026 UBOS blog post claims the Galaxy S26 ships with crash detection, citing Android Police. — [UBOS](https://ubos.tech/news/samsung-galaxy-s26-adopts-pixels-advanced-safety-features/). This is **contradicted** by April 2026 Samsung Community and Reddit user threads saying Samsung phones still have no crash detection. — [Samsung Community](https://us.community.samsung.com/t5/Galaxy-S26/Crash-Detection-Help/td-p/3534206); [Reddit r/galaxys26ultra](https://www.reddit.com/r/galaxys26ultra/comments/1wmevwp/crash_detection_on_samsung_devices/). I found no official Samsung announcement.
- **Samsung (watches):** The Galaxy Watch has Fall Detection, which is off by default and enabled through the Galaxy Wearable app. The flow is a popup, sound and vibration with a 60-second window. If the user does not respond, an SOS with location goes to emergency contacts. Newer models (for example Watch Ultra) can also call 911 or 112 after a configurable countdown. Samsung warns that vigorous exercise can trigger it. — [Samsung UK support](https://www.samsung.com/uk/support/mobile-devices/how-to-set-up-fall-detection-on-the-galaxy-watch/); [Samsung CA support](https://www.samsung.com/ca/support/mobile-devices/galaxy-watch-detect-falls); [O2 Galaxy Watch Ultra guide](https://www.o2.co.uk/help/phones-sims-and-devices/samsung/galaxy-watch-ultra-android-wear-os/set-up-and-first-use/turn-fall-detection-on-or-off)
- **Xiaomi:** Around July 2025, Xiaomi added car crash detection to its Security app (China). On a severe impact the phone sounds an alarm and asks whether the driver is OK. With no response it calls 120 (China's emergency number) and can send location to preset contacts. — [Gizchina](https://www.gizchina.com/2025/07/16/xiaomi-just-taught-your-phone-to-call-for-help-after-a-crash/)

### Inferences
- Outside Pixel, there is no Android-wide (AOSP or Google Play Services) crash detection for non-Pixel phones in Western markets. This leaves a large Android installed base (Samsung, Motorola, OnePlus and others) without a free OS-level crash feature, which is the gap third-party apps (Life360, insurer apps) fill.
- Pixel's limits create gaps even for Pixel owners: about 21 countries, no roaming, a SIM required, Battery Saver must be off, opt-in permissions, and no Hindi or other major regional languages.
- Google's 60-second window is much longer than Apple's (10 seconds plus 20-30 seconds). That tradeoff probably reduces false emergency calls at the cost of slower dispatch.

### Gaps
- I could not confirm the exact launch date of Pixel Car Crash Detection from a primary source here. It is commonly cited as Pixel 3 / US in 2019, but that is not verified in these notes.
- No official confirmation on whether the Galaxy S26 or One UI 8/9 enables crash detection. The sources conflict.
- I found no reliable information on crash detection from OnePlus, Motorola, Huawei, Honor, vivo or OPPO. A broad search returned nothing citable.
- Pixel 10 and Android 16/17-era changes to Car Crash Detection (if any) were not found.

---

## 2. Apple iPhone and Apple Watch Crash Detection

### Takeaway
Apple's Crash Detection launched September 2022 with iPhone 14 / iOS 16 and Apple Watch Series 8 / SE 2 / Ultra / watchOS 9. It is free and on by default. It uses a 10-second alert, then a countdown, then an automatic call with a recorded message plus coordinates, then notification of emergency contacts. Satellite fallback is available. Early false positives on roller coasters and ski slopes led to software "optimizations" in iOS 16.1.2 (Nov 2022) and iOS 16.3.1 (Feb 2023).

### Cited Findings
- Supported devices: iPhone 14 or later with iOS 16 or later; Apple Watch Series 8 or later, SE (2nd gen) and Ultra or later with watchOS 9 or later. It is **on by default**. — [Apple Support 104959](https://support.apple.com/en-us/104959)
- Detects severe front-impact, side-impact and rear-end collisions and rollovers in sedans, minivans, SUVs, pickups and other passenger cars. — [Apple Support 104959](https://support.apple.com/en-us/104959)
- Flow: the device sounds an alarm and shows an alert for 10 seconds. On iPhone, an emergency call starts automatically **after 20 seconds** unless cancelled. On Apple Watch, if the user does not respond after 10 seconds, a 30-second countdown begins with an alarm and wrist taps, then the call is placed. — [Apple iPhone User Guide](https://support.apple.com/guide/iphone/manage-crash-detection-iph948a628e9/ios); [Apple Watch User Guide](https://support.apple.com/guide/watch/manage-crash-detection-apd3a60b5576/watchos)
- If the user is unresponsive, the device plays an audio message telling emergency services there was a severe crash and gives latitude and longitude with an approximate search radius. It then messages emergency contacts with the user's location. — [Apple Watch User Guide](https://support.apple.com/guide/watch/manage-crash-detection-apd3a60b5576/watchos)
- If there is no cellular or Wi-Fi, iPhone tries Emergency SOS via satellite where available. Apple Watch Ultra 3 can use satellite directly. — [Apple Support 104959](https://support.apple.com/en-us/104959); [Apple iPhone User Guide](https://support.apple.com/guide/iphone/manage-crash-detection-iph948a628e9/ios)
- Crash Detection defers to the vehicle's own integrated crash-call system (for example eCall or OnStar) and does not cancel existing emergency calls. It does cancel non-emergency calls in progress. With a watch worn, the call is placed by iPhone and alerts are routed through the watch. — [Apple Support 104959](https://support.apple.com/en-us/104959); [Apple iPhone User Guide](https://support.apple.com/guide/iphone/manage-crash-detection-iph948a628e9/ios)
- Detection uses an upgraded gyroscope and a high-g accelerometer, plus other sensors. — [Mashable](https://mashable.com/article/apple-iphone-crash-detection-how-it-works)
- **False positives:** In October 2022 the WSJ reported roller-coaster triggers. A county dispatch center traced a call to the Joker coaster at Six Flags Great America. — [iMore](https://www.imore.com/ios/your-iphone-14-might-not-activate-crash-detection-on-a-roller-coaster-anymore)
- In winter 2022-23, Summit County (CO) 911 received 71 automated crash calls from skiers' iPhones and Watches at four ski areas over one weekend, none of them real emergencies. Some responders asked users to disable the feature. — [Appleosophy](https://appleosophy.com/2023/01/14/crash-detection-also-causes-false-911-calls-from-skiers/); [MobileSyrup](https://mobilesyrup.com/2022/12/01/skiing-can-trigger-your-iphone-14s-crash-detection-feature/)
- **Apple's fixes:** iOS 16.1.2 (late Nov 2022) included "Crash Detection optimizations on iPhone 14 and iPhone 14 Pro models", without details. — [TechCrunch](https://techcrunch.com/2022/11/30/apples-ios-update-just-dropped-with-security-fixes-and-crash-detection-improvements/); [BGR](https://www.bgr.com/tech/apple-releases-ios-16-1-2-with-crash-detection-optimization/). iOS 16.3.1 (Feb 13, 2023) included further optimizations. — [9to5Mac](https://9to5mac.com/2023/02/13/ios-16-3-1-iphone-14-crash-detection/); [MobileSyrup](https://mobilesyrup.com/2023/02/13/apples-crash-detection-goes-through-another-round-of-optimizations/)

### Inferences
- Apple's default-on, ecosystem-wide approach (every iPhone since the 14) is the benchmark that Android lacks outside Pixel. This asymmetry is the core market opportunity for Android.
- The false-positive episode shows that PSAP burden (dispatcher load) is a real deployment risk and regulatory/PR concern. Any new entrant needs a strong cancel UX and a confidence threshold.

### Gaps
- I found no current (2024-2026) data on residual false-positive rates or whether Apple changed the countdown lengths since 2023.
- Apple does not publish country availability for Crash Detection separately. It depends on emergency-calling support.

---

## 3. Third-party consumer apps

### Takeaway
Life360 is the main cross-platform consumer competitor. Basic crash detection is free (crashes at 25+ mph alert the Circle), and paid tiers add live-agent emergency dispatch. Other consumer apps target niches: motorcycles (Liberty Rider, REALRIDER, calimoto with Bosch) and outdoor or cycling (Garmin). OnStar Guardian brings GM's call-center model to any car for about $15/month.

### Cited Findings
**Life360 (iOS/Android, family locator)**
- Membership pricing page (US, monthly): Silver $9.99 (lists "Crash Detection"), Gold $16.99 ("Crash Detection + Emergency Dispatch"), Platinum $24.99 (adds $1M stolen funds reimbursement and more). A free tier exists. — [Life360 Plans & Pricing](https://www.life360.com/plans-pricing)
- Life360's blog "Safety for all: Introducing Crash Detection for all Life360 members" makes basic Crash Detection free for every member. It detects collisions at **25 mph or more** and alerts Circle members and emergency contacts. Emergency dispatch with live agents (the agent calls the driver and sends an ambulance if there is no answer or help is confirmed) was tied to the paid Driver Protect tier. — [Life360 blog](https://www.life360.com/blog/safety-for-all-introducing-crash-detection-for-all-life360-members); [Collision Repair Mag](https://www.collisionrepairmag.com/news/collision-repair/article/15720175/life360-offers-crash-detection-for-free-in-time-for-aprils-distracted-driving-awareness-month). The search summary dated this to 2020. I could not confirm the date.
- Life360's help center (summarized via search; page returned 403 to direct fetch) says dispatch is available for Silver, Gold and Platinum members in the US, Canada, UK, Australia and New Zealand, and for Gold members in Austria, Belgium, France, Ireland, Italy and Germany. It may miss fender-benders and rear-end collisions, and needs Drive Detection on and Power Saving off. — [Life360 Help: Free Crash Detection](https://support.life360.com/hc/en-us/articles/23053410919063-Life360-Free-Crash-Detection). This **conflicts** with the pricing page, which shows dispatch only from Gold up.
- Historical price: the Driver Protect add-on was $7.99/month or $69.99/year (2020-era). — [Collision Repair Mag via search](https://www.collisionrepairmag.com/news/collision-repair/article/15720175/life360-offers-crash-detection-for-free-in-time-for-aprils-distracted-driving-awareness-month)
- In Australia, the Gold and Platinum bundles start at $16.99/month. — [Life360 Investor Relations](https://investors.life360.com/news-releases/news-release-details/life360-enhances-family-safety-app-launch-new-features-and)

**OnStar Guardian (GM; iOS/Android, any vehicle)**
- Smartphone-sensor crash detection alerts an OnStar Advisor, who can send help. Other features include Mobile Crisis Assist and Roadside. It is included for eligible OnStar members. The standalone plan is about **$15/month** and covers up to 7 family members. Trial terms have varied (30 days, later up to 3 months). The listing says "available on select Android devices" and notes the app "may not transmit all crash data." — [App Store listing](https://apps.apple.com/us/app/-/id1480411487)
- Canada: plans reported at C$14.99-49.99/month (older Carscoops report). — [Carscoops](https://www.carscoops.com/?p=2257904)

**Motorcycle apps**
- **Liberty Rider** (France/Belgium/Italy focus): "SOS Accident" automatic accident detection is in the Premium tier at €3.99/month, which is free when offered through a partner insurer. The free tier has no crash detection. Insurer and bank partners include MACIF, AXA, Matmut, Groupama, Allianz, MAIF, Banque Populaire, Caisse d'Epargne, Ethias, P&V and Vivium. — [Liberty Rider site](https://liberty-rider.com/); [Banque Populaire](https://www.banquepopulaire.fr/assurer/rouler-deux-roues-serenite-liberty-rider/) (bank values premium access at €48/year for its two-wheeler policyholders)
- **REALRIDER** (UK): about £3.99/month after a 30-day trial, or £36/year (C$4.99/month in Canada). The current App Store listing claims emergency-service connectivity in the UK, Ireland, Europe, North America, Australia and NZ. Older listings said UK and Canada only, and earlier UK only. It is integrated with UK emergency services (BAPCO 999 apps). — [App Store](https://apps.apple.com/us/app/-/id1080699050); [MSL Magazine](https://www.mslmagazine.co.uk/get-realrider-realsafe-for-half-price/); [BAPCO](https://www.bapco.org.uk/what_we_do/999apps/realrider.aspx)
- **Bosch Help Connect**: an automatic emergency call system for motorcycles that uses the bike's MSC inertial sensor (sampled about 100 times per second), or in the calimoto and COBI.Bike apps, the phone's accelerometer with the phone mounted on the handlebars. It sends GPS and incident data to the **Bosch Service Center**, which relays it to emergency services. Riders can store medical info and an emergency contact. Availability: users with a German SIM in 11 European countries (some reports say Germany only). — [Motorcycle News](https://motorcyclenews.com/news/new-tech/bosch-accident-detection-system); [Harleysite (calimoto + Bosch)](https://www.harleysite.de/en/calimoto-launches-automatic-emergency-call-system-for-motorcyclists-with-bosch/); [webBikeWorld](https://old.webbikeworld.com/?p=88060)

**Cycling and outdoor (Garmin)**
- Garmin Incident Detection (Edge bike computers and watches, during certain GPS activities) shows a 15-second cancellable alert, then the Garmin Connect app texts and emails the user's name and GPS location to assistance contacts. It needs a paired phone with data. Garmin states the app "does not contact emergency services". — [Garmin Edge manual](https://www8.garmin.com/manuals/webhelp/GUID-08ACA9FC-DEE6-4C8D-8A95-F62181C512E9/EN-AU/GUID-F109FB24-B55D-49D2-B388-D042E7FBA965.html); [Garmin manual](https://www8.garmin.com/manuals/webhelp/GUID-708A8F4D-9A78-49CF-9528-DE109BBCC472/EN-US/GUID-8F7F297D-23C5-4256-8939-16A4D04408C1.html)
- Garmin inReach: a manual SOS over the global Iridium satellite network to Garmin Response, a 24/7 coordination center that liaises with SAR and contacts. It requires a device and a subscription. — [Garmin via Nasdaq](https://www.nasdaq.com/press-release/garmin-releases-annual-inreach-sos-report-2024-02-29); [Motorcycle.com](https://www.motorcycle.com/2022/10/25/motorcycle-news/garmin-inreach-aids-in-10000-incidents/)

### Inferences
- Life360 has turned crash detection into a freemium funnel: free detection with Circle alerts drives upgrades to dispatch tiers ($9.99-24.99/month). This is the main consumer pricing benchmark for an Android app.
- Professional-monitoring (call center) models (Life360 dispatch, OnStar, Bosch Service Center, Liberty Rider) work around the false-positive problem by having a human verify before contacting a PSAP. That is a different design from Apple/Google direct PSAP calling.
- Motorcycle crash detection in Europe is commonly distributed **B2B2C through insurers** (Liberty Rider), which is a model an Android crash app could copy.

### Gaps
- I could not open the Life360 help center directly (403), and the dispatch tier and country list conflicts with the pricing page. The current Life360 free-tier crash detection behaviour and countries need verification.
- "Help Flash" (the Spanish V16 beacon brand) and "Sosmart" were not researched in depth. I found no sources on Sosmart's current status, and Help Flash is a roadside beacon, not crash-detection software.
- No independent accuracy or false-positive data for any third-party app.

---

## 4. B2B telematics SDK vendors and insurer apps

### Takeaway
In the US, crash detection reaches most drivers through insurer apps powered by a few B2B telematics vendors. CMT (which absorbed TrueMotion) is dominant. Arity (Allstate) also licenses. Zendrive went to Intuit in 2024. Insurers offer crash detection free as part of UBI or claims apps (Progressive Accident Response since Nov 2024, State Farm collision detection since Oct 2025, Allstate Drivewise). Monetization is claims cost reduction and premium pricing, not subscriptions.

### Cited Findings
- **Cambridge Mobile Telematics (CMT)**: the DriveWell platform has done mobile crash detection since 2016. In 2022 it launched DriveWell Crash & Claims (proactive FNOL), and it claimed over 2.1 million drivers protected daily. Customers include State Farm, Liberty Mutual, Nationwide, Plymouth Rock, Mercury and Erie, and in Japan Aioi Nissay Dowa. — [BusinessWire 2022](https://www.businesswire.com/news/home/20220906005301/en/Cambridge-Mobile-Telematics-Launches-Next-Generation-Platform-for-Proactive-Claims); [BusinessWire 2021 (Aioi)](https://www.businesswire.com/news/home/20210309005757/en/Cambridge-Mobile-Telematics-Partners-With-Aioi-Nissay-Dowa-Insurance-to-Launch-Japans-First-Large-Scale-Usage-based-Insurance-Program)
- CMT acquired TrueMotion (closed June 2021), after which it served 21 of the 25 largest US auto insurers. It also acquired Amodo (Europe). — [Goodwin](https://www.goodwinlaw.com/en/news-and-events/news/2021/06/06_17-truemotion-acquired-by-cambridge-mobile); [Aftermarket News](https://www.aftermarketnews.com/cambridge-mobile-telematics-acquires-truemotion/); [Coverager](https://coverager.com/cambridge-mobile-telematics-acquires-amodo/)
- **Zendrive** (smartphone-sensor driving analytics SDK) was acquired by **Intuit** in June 2024. — [CB Insights](https://www.cbinsights.com/company/zendrive/financials) (aggregator; no primary source checked)
- **Progressive Accident Response** launched November 2024. Phone sensors and ML detect a major crash, Progressive contacts the driver, and the driver can request EMS or a tow via the app or a live agent. If there is no response and the crash looks serious, EMS is dispatched. A claim is auto-started when a tow or ambulance is sent. It is free. In November 2025 it was upgraded to CMT DriveWell Fusion, with more than 1.5M customers using it. — [Repairer Driven News 2024](https://www.repairerdrivennews.com/2024/12/02/progressive-launches-new-in-app-feature-to-detect-major-accidents/); [Repairer Driven News 2025](https://www.repairerdrivennews.com/2025/11/21/cambridge-mobile-telematics-ai-platform-now-paired-with-progressives-accident-response/); [Coverager](https://coverager.com/progressive-insurance-accident-response-powered-by-cambridge-mobile-telematics-provides-real-time-crash-detection/)
- **State Farm** collision detection in its mobile app (reported Oct 6, 2025) detects collisions from phone sensors, contacts emergency services with GPS location, and offers roadside for non-emergencies and a claim flow after a tow. It is voluntary and was piloted first in Illinois and Florida. The tech vendor was not named in the article, though CMT lists State Farm as a DriveWell customer. — [Repairer Driven News](https://www.repairerdrivennews.com/2025/10/06/state-farm-introduces-collision-detection-in-its-mobile-app/); [BusinessWire 2022](https://www.businesswire.com/news/home/20220906005301/en/Cambridge-Mobile-Telematics-Launches-Next-Generation-Platform-for-Proactive-Claims)
- **Allstate Drivewise / Arity**: Drivewise is an opt-in UBI feature (scores speed, braking and phone use) in the Allstate Mobile app, which also includes crash detection and 24/7 roadside. Allstate claims Drivewise users have 25% fewer severe collisions (company analysis). Drivewise savings are not available in California or Alaska. Arity (founded by Allstate in 2016) sells crash detection to other carriers to alert emergency services, notify loved ones and kick off claims. — [Allstate Newsroom](https://www.allstatenewsroom.com/news/drivewise-25/); [Arity](https://arity.com/?p=4760)
- **Privacy and legal risk:** The Texas AG sued Allstate and Arity (January 2025) over alleged covert collection of location and driving data via apps. On April 11, the court dismissed Arity 875 LLC and The Allstate Corporation from the case (the article does not specify the full outcome). — [Repairer Driven News](https://www.repairerdrivennews.com/2025/10/06/state-farm-introduces-collision-detection-in-its-mobile-app/)

### Inferences
- The US market is a B2B2C oligopoly (CMT plus Arity, with Zendrive now inside Intuit). A new crash-detection product would either license to insurers (competing with CMT) or go direct-to-consumer (competing with Life360 and free OEM features).
- Insurers bundle crash detection with driving-behavior scoring, roadside and auto-FNOL, so "crash detection alone" is becoming a commodity feature in insurer apps.
- Data-privacy litigation (Texas v. Allstate/Arity) is a material risk for any app that collects continuous driving telemetry.

### Gaps
- No public SDK pricing for CMT, Arity or Zendrive. It is B2B and negotiated.
- No confirmation of which vendor powers State Farm's 2025 collision detection.

---

## 5. In-vehicle and connected-car systems (eCall, OEM telematics)

### Takeaway
In the EU, automatic crash calling is a regulatory baseline. eCall (an automatic 112 call plus a Minimum Set of Data) has been mandatory in new cars and light vans since March 2018, and Next-Generation eCall over 4G/5G is being phased in during 2026-2027 as 2G/3G networks shut down. In the US, OEM telematics (OnStar, and Hyundai/BMW connected services) cover connected cars under subscription. Apple defers to these built-in systems.

### Cited Findings
- EU eCall has been mandatory in new passenger cars and light vans since March 2018. It automatically (or manually) calls 112 and sends a Minimum Set of Data (location, direction of travel, vehicle info). — [EENA](https://eena.org/our-work/eena-special-focus/ecall/); [web.de](https://web.de/magazine/auto/notruf-system-next-generation-ecall-2026-bedeutet-autofahrer-41409960)
- NG eCall (packet-switched, 4G/5G) under Delegated Regulation (EU) 2024/1084: all PSAPs must handle NG eCall by 1 January 2026. Sources diverge on vehicle dates (new type approvals from 2026, all new vehicles from 2027). Standards were updated by Regulation (EU) 2025/1871 (EN 17184:2024 and EN 17240:2024 mandatory for new type approvals from 1 January 2027). Legacy 2G/3G eCall units may stop working as networks close. — [3G4G blog, Aug 2026](https://blog.3g4g.co.uk/2026/08/?hl=en); [L'Automobiliste](https://lautomobiliste.fr/04/10/2025/des-2026-impossible-dacheter-une-voiture-neuve-sans-ce-dispositif-impose/); [Motor.es](https://www.motor.es/noticias/europa-sistema-llamada-emergencia-ecall-2026-2025110215.html)
- Apple Crash Detection lets the vehicle's integrated crash-call system proceed unchanged. — [Apple Support 104959](https://support.apple.com/en-us/104959)

### Inferences
- In the EU, the addressable market for phone-based car crash detection is mainly **older vehicles without eCall** (pre-2018), plus vehicles whose 2G/3G eCall units lose network as legacy networks shut down, as well as motorcycles, bikes, and passengers in non-equipped vehicles.
- Phone apps should detect or defer to built-in eCall/OnStar to avoid duplicate PSAP calls, as Apple does.

### Gaps
- I did not research Hyundai Bluelink, BMW ConnectedDrive SOS or Toyota Safety Connect pricing in detail (no searches completed for these within the tool budget).
- No data on the share of the EU car fleet equipped with eCall.

---

## 6. Common feature set and business models (cross-cutting synthesis)

### Takeaway
The table-stakes feature set is: sensor-fusion crash detection, a countdown with cancel (10-60 seconds), an automatic emergency call with a voice message and coordinates, and SMS or live location to contacts. Differentiators are medical ID, live-agent verification and dispatch, roadside, auto-claims, driving scores, and family location. Business models split three ways: free OEM features (Apple, Google, Xiaomi), consumer subscriptions (Life360, OnStar Guardian, REALRIDER, Liberty Rider), and B2B or insurer-funded models (CMT, Arity, Bosch, insurer apps, Liberty Rider through insurers).

### Cited Findings (competitor matrix)

| Product | Platform | Detection | Countdown / cancel | Who is contacted | Extras | Pricing / model | Regions |
|---|---|---|---|---|---|---|---|
| Google Pixel Car Crash Detection | Pixel 4a+ phones; Pixel Watch paired to Pixel | On-device sensors, opt-in | 60 s voice and screen prompt; "Cancel"/"I'm OK" | PSAP directly (recorded message plus location via ELS); Emergency Sharing to contacts | Safety Check, Emergency Sharing, Satellite SOS (Pixel 9, US) | Free (OEM) | 21 countries, 12 languages ([Google](https://support.google.com/pixelphone/answer/7055029?hl=en)) |
| Apple Crash Detection | iPhone 14+ / Watch S8, SE2, Ultra+ | Gyro, high-g accelerometer, etc.; default on | 10 s alert, then call at 20 s (iPhone) or a further 30 s countdown (Watch) | PSAP directly (audio message plus lat/long); SMS to contacts | Satellite fallback, Medical ID | Free (OEM) | Wherever emergency calling works ([Apple](https://support.apple.com/en-us/104959)) |
| Xiaomi crash detection | Xiaomi phones (Security app) | Sensors | Alarm plus "are you OK" prompt | Calls 120; location to contacts | n/a | Free (OEM) | China, from about Jul 2025 ([Gizchina](https://www.gizchina.com/2025/07/16/xiaomi-just-taught-your-phone-to-call-for-help-after-a-crash/)) |
| Samsung Galaxy phones | n/a | Sensor present in firmware, feature not enabled (S26 status disputed) | n/a | n/a | n/a | n/a | n/a ([Android Authority](https://www.androidauthority.com/samsung-galaxy-s25-car-crash-detection-sensor-3511840/)) |
| Samsung Galaxy Watch fall detection | Galaxy Watch + Galaxy Wearable | Fall (not car crash) | 60 s | SOS plus location to contacts; newer models can call 911/112 | n/a | Free (OEM) | Varies ([Samsung](https://www.samsung.com/uk/support/mobile-devices/how-to-set-up-fall-detection-on-the-galaxy-watch/)) |
| Life360 | iOS/Android | Phone sensors, 25+ mph | Not documented here | Circle and contacts (free); live agent plus dispatch (paid) | Family location, driver reports, roadside (higher tiers) | Freemium: Silver $9.99, Gold $16.99, Platinum $24.99/mo ([Life360](https://www.life360.com/plans-pricing)) | Dispatch in US/CA/UK/AU/NZ plus some EU (tier conflict noted) |
| OnStar Guardian | iOS/select Android | Phone sensors | n/a | OnStar Advisor, then EMS | Roadside, crisis assist, up to 7 users | About $15/mo standalone; included with OnStar ([App Store](https://apps.apple.com/us/app/-/id1480411487)) | US/Canada |
| Progressive Accident Response | Progressive app | Phone sensors plus ML (CMT since Nov 2025) | Progressive contacts driver | Live agent; EMS if no response | Tow, auto-claim | Free for policyholders ([RDN](https://www.repairerdrivennews.com/2025/11/21/cambridge-mobile-telematics-ai-platform-now-paired-with-progressives-accident-response/)) | US |
| State Farm collision detection | State Farm app | Phone sensors | n/a | EMS with GPS | Roadside, claims | Free, opt-in ([RDN](https://www.repairerdrivennews.com/2025/10/06/state-farm-introduces-collision-detection-in-its-mobile-app/)) | IL and FL pilot (Oct 2025) |
| Allstate Drivewise / Arity | Allstate app; Arity SDK | Phone sensors | n/a | Help connect; Arity lets carriers alert EMS and loved ones | Driving score, roadside, UBI discount | Free with policy; Arity B2B ([Allstate](https://www.allstatenewsroom.com/news/drivewise-25/), [Arity](https://arity.com/?p=4760)) | US (no Drivewise savings in CA/AK) |
| CMT DriveWell | B2B SDK to insurers | Phone, tag and sensor fusion | Insurer-defined | Insurer, EMS; proactive claims | Driving scores, FNOL | B2B licensing ([BusinessWire](https://www.businesswire.com/news/home/20220906005301/en/Cambridge-Mobile-Telematics-Launches-Next-Generation-Platform-for-Proactive-Claims)) | US, Japan, Europe (via Amodo) |
| Zendrive | B2B SDK | Phone sensors | n/a | n/a | Driving analytics | Acquired by Intuit Jun 2024 ([CB Insights](https://www.cbinsights.com/company/zendrive/financials)) | n/a |
| Bosch Help Connect | Bike IMU or phone (calimoto, COBI.Bike) | Crash algorithm | n/a | Bosch Service Center, then EMS | Medical info | B2B / OEM / app partner | German SIM, 11 EU countries ([MCN](https://motorcyclenews.com/news/new-tech/bosch-accident-detection-system)) |
| Liberty Rider | iOS/Android (motorcycle) | Phone sensors | n/a | n/a | Navigation, community | €3.99/mo Premium or free via insurer ([Liberty Rider](https://liberty-rider.com/)) | FR/BE/IT (inferred) |
| REALRIDER | iOS/Android (motorcycle) | Phone sensors | n/a | UK emergency services link | Medical info | About £3.99/mo or £36/yr ([MSL](https://www.mslmagazine.co.uk/get-realrider-realsafe-for-half-price/)) | UK, IE, EU, NA, AU/NZ (claimed) |
| Garmin Incident Detection | Edge / watches + Garmin Connect | Device sensors during activities | 15 s | Contacts only (SMS/email); **no** EMS | inReach satellite SOS to Garmin Response (separate subscription) | Included with device ([Garmin](https://www8.garmin.com/manuals/webhelp/GUID-708A8F4D-9A78-49CF-9528-DE109BBCC472/EN-US/GUID-8F7F297D-23C5-4256-8939-16A4D04408C1.html)) | Global (phone data needed) |
| EU eCall / NG eCall | Built into vehicle | Airbag and vehicle sensors | n/a | 112 plus MSD | n/a | Regulatory mandate (cost in vehicle price) | EU new cars since Mar 2018; NG eCall 2026-27 ([EENA](https://eena.org/our-work/eena-special-focus/ecall/)) |

### Inferences
- **Countdown design spectrum:** Garmin 15 s, Apple 10+20 s (iPhone) or 10+30 s (Watch), Google 60 s, Samsung Watch fall 60 s. Direct-to-PSAP systems (Apple, Google) use a recorded voice message and coordinates. Agent-mediated systems (Life360, OnStar, Progressive, Bosch) insert a human verification step.
- **Business models:** (1) free OEM feature used as platform differentiation (Apple, Google, Xiaomi); (2) consumer subscription of about $4-25/month (Life360, OnStar Guardian, REALRIDER, Liberty Rider); (3) B2B SDK licensing to insurers (CMT, Arity, Zendrive) or OEMs (Bosch), monetized through claims savings and UBI pricing; (4) regulatory mandate (eCall).
- **Gap for Android:** non-Pixel Android users (most of the global Android base) have no free OS-level car crash detection outside China (Xiaomi). Their options are Life360 (dispatch only on paid tiers in limited countries), insurer apps (US-centric, policyholder-only), or niche motorcycle apps. Regions not covered by Pixel (for example Latin America, Southeast Asia except Singapore, the Middle East and Africa) have very few options.

### Gaps
- Life360 crash countdown and flow details were not confirmed from a primary page.
- No reliable data found on market share or user counts per product, except Progressive (more than 1.5M using Accident Response) and CMT (more than 2.1M protected daily, 2022).
- Not researched within budget: Hyundai Bluelink, BMW ConnectedDrive, Toyota, Ford SYNC 911 Assist pricing; Sosmart; Help Flash (a V16 beacon, not crash detection); Huawei, Honor, OnePlus and vivo.
