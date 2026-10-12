# Pedestrian–Vehicle Collision Detection with Smartphone / Wearable Sensors: Studies, Biomechanics, Products, Datasets

## Q1. Has smartphone/wearable detection of a pedestrian being struck by a vehicle been studied (vs. general fall detection)?

### Takeaway
I found no peer-reviewed study that detects a pedestrian being struck by a vehicle from phone or wearable sensors and validates it on real or staged collisions. The topic appears only in patents (e.g. Sony) and as a side-mention in fall/crash detection work. Phone sensors in pedestrian-safety research are used before a collision (distracted-walking detection, V2P warnings, UWB time-to-collision), not to detect the impact. This gap is a key finding.

### Cited Findings
- **Sony patent US10803721B2 ("Method for supporting a user and user device")** was filed 20 Mar 2019 (EP priority 26 Mar 2018) and granted 13 Oct 2020. It names "a person ... hit by a car or falls" and cyclists "getting hit by a car" as use cases. Its logic works in stages:
  1. A smartphone or smartwatch accelerometer reading above a threshold starts vital-sign measurement (heart rate, blood pressure, skin response, SpO2, skin temperature).
  2. Help is signalled only if a vital sign moves away from the user's pre-event baseline by more than a threshold. A small change notifies a private contact; a large one (e.g. >100%) notifies emergency services.
  3. The device auto-sends if the user does not respond.
  4. The alert is suppressed if gait and vital signs return to normal quickly.
  5. The patent mentions hit-and-run victims with no bystanders.
  The patent has no reported accuracy. — [Google Patents US10803721B2](https://patents.google.com/patent/US10803721B2/en); [USPTO PDF](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10803721)
- Searches for "pedestrian accident detection smartphone accelerometer" return distracted-walking work, not impact detection:
  - **GaitX (2025)** uses IMU features with XGBoost/Random Forest. It reports 92.3% average accuracy across 21 subjects, with the phone either handheld or pocketed. — [PMC12390545](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12390545/)
  - **SaferCross** used 5 volunteers and reports >90% accuracy at detecting phone-viewing while walking. — [arXiv 1805.00442](https://arxiv.org/pdf/1805.00442)
- Other pedestrian-safety phone work is about avoiding collisions, not detecting them:
  - A V2VRU paper fuses phone GPS, accelerometer and gyro with a Kalman filter to get past 1 Hz GPS updates. — [Electronics 13(2):331, 2024](https://doi.org/10.3390/electronics13020331)
  - A UWB time-to-collision prototype sends a smartphone alert when TTC < 3 s. — [Sensors/PMC10142004](https://pmc.ncbi.nlm.nih.gov/articles/PMC10142004/)
  - ObstacleWatch uses the phone's acoustics to detect obstacles. — [arXiv 2106.01838](https://arxiv.org/pdf/2106.01838)
  - SECREDAS computes the pedestrian's collision risk on the phone with UWB positioning. — [arXiv 2303.18170](https://arxiv.org/pdf/2303.18170)
- Smartphone "accident detection" literature (WreckWatch, iBump, surveys) is about vehicle occupants:
  - It typically combines accelerometer and microphone thresholds with GPS and SMS alerts. — [WreckWatch](http://www.dre.vanderbilt.edu/~jules/wreckwatchj.pdf); [iBump](http://www.aloul.net/Papers/faloul_iaict14.pdf); [IJACSA survey 2018](https://thesai.org/Downloads/Volume9No9/Paper_52-A_Survey_on_Smartphone_based_Accident.pdf)
  - One occupant system raises an accident flag above 4 g but notes that g-force alone is not enough. — [PMC6540187](https://pmc.ncbi.nlm.nih.gov/articles/PMC6540187/)
- Fall-detection papers using pocketed phones model the fall process but do not include vehicle-impact classes:
  - A cascaded classifier reports 92% sensitivity and 99.75% specificity on 450 test actions. — [Pocket fall detection](https://www.researchgate.net/publication/269281602_A_Smart_Phone-Based_Pocket_Fall_Accident_Detection_Positioning_and_Rescue_System)
  - The uCare app uses a five-phase fall model. — [Review, ISI 24(6)](https://www.iieta.org/journals/isi/paper/10.18280/isi.240602)

### Inferences
- A pedestrian strike is a fall with an extra external high-energy impact stage. Existing fall-detection pipelines (impact peak, then orientation change, then immobility) are the closest working baseline, but none has been tested on collision data.
- The Sony patent's design is the only explicit pedestrian design found. It uses an accelerometer trigger, then a physiological check, then graded escalation. This suggests industry sees the false-positive problem as needing modalities beyond the IMU.

### Gaps
- I found no study with accuracy, sensitivity or false-alarm figures for detecting a pedestrian struck by a vehicle on a phone or wearable.
- I could not fully search IEEE Xplore or Google Scholar; my coverage came from web search. A small conference paper or student thesis may exist that I did not surface.
- I found no study recording phone or wearable IMU signals during pedestrian dummy or PMHS crash tests.

## Q2. What does pedestrian impact biomechanics say about the acceleration signature, and how does it differ from a fall or phone drop?

### Takeaway
PMHS and dummy studies show a two-impact event:
1. A primary vehicle strike, with leg/bumper contact at t=0 and head contact on the hood or windscreen about 125–141 ms later at 40 km/h.
2. A secondary ground impact, with highly variable kinematics (wrap trajectory, forward projection, vault, somersault).

The highest body accelerations come in the leg-contact-to-head-strike window. That is unlike a simple fall (a single ground impact) or a phone drop (free fall with no body-motion context). I could not retrieve published peak g values for torso or pelvis.

### Cited Findings
- **Forman et al., IRCOBI 2015** tested 3 male PMHS struck laterally in mid-gait by a generic sedan buck at 40 km/h. Speeds were within 1.5% of target. Head contact came **135–141 ms after leg contact**, and head wrap-around distance was 2175–2200 mm. The vehicle and subject interacted for about 250 ms before the sled braked. — [IRCOBI 2015 IRC-15-49](https://www.ircobi.org/wordpress/downloads/irc15/pdf_files/49.pdf)
- An NHTSA full-scale PMHS pilot found head impact at about 125 ms. The period between bumper contact and head strike is when the pedestrian is accelerated up to vehicle speed, and it carries the highest forces and accelerations. — [NHTSA Stapp/BIO 2004 32-11](https://www-nrd.nhtsa.dot.gov/pdf/BIO/Proceedings/2004_32/32-11.pdf) (summary via search snippet; the PDF returned 403 to direct fetch)
- **Trajectory types (Ravani et al. 1981):** wrap trajectory, forward projection, fender vault, roof vault and somersault. A wrap occurs when the car strikes below the pedestrian's centre of gravity. Ground-impact mechanisms 1–6 are classified by trajectory and torso angle at ground contact; Crocetta et al. 2015 split mechanism 5 into 5a/5b. — [Accid. Anal. Prev. 2022, ground-impact kinematics](https://www.sciencedirect.com/science/article/abs/pii/S0001457522002287)
- **Ground impact:** head acceleration is driven mainly by the vertical component of impact velocity; the horizontal change is small. If the torso angle at ground contact is <90°, head vertical velocity stays below about 2.9 m/s, which the authors tie to the head-injury threshold. — [Accid. Anal. Prev. 2022](https://www.sciencedirect.com/science/article/abs/pii/S0001457522002287)
- In German real-world reconstructions (PC-Crash plus MADYMO), the 50% risk of AIS2+ and AIS3+ pedestrian head injury occurs at impact speeds of 38.87 km/h and 54.39 km/h. The study compared primary (car) and secondary (ground) head impacts. — [Chalmers 2012 publication 162986](https://research.chalmers.se/en/publication/162986)
- An FE model predicted head impact time with R²=0.979 and RMSE=6.61 ms, using front-end geometry, pedestrian size, speed and WAD as inputs. — [Springer review of pedestrian HBM simulation](https://link.springer.com/article/10.1007/s11831-023-09949-2)
- Vehicle impact speed can be estimated from throw distance and post-impact trajectory. — [IRCOBI 2002](https://www.ircobi.org/wordpress/downloads/irc0111/2002/Session4/4.1.pdf)
- Adult headform impactor tests underestimate head acceleration and HIC compared with full-body PMHS tests. — [IRCOBI 2015](https://www.ircobi.org/wordpress/downloads/irc15/pdf_files/49.pdf)

### Inferences
- **Expected IMU signature on the body:**
  1. A sharp lateral or forward acceleration at the leg/pelvis, coinciding with bumper contact.
  2. Rapid rotation, often over 90° of trunk rotation within about 100–150 ms (wrap onto hood).
  3. A head/torso strike on the vehicle at about 125–140 ms (at 40 km/h).
  4. An airborne or sliding phase lasting hundreds of ms to a few seconds.
  5. A second, ground-impact peak.
  6. Possible immobility.

  This multi-peak pattern, with a large horizontal velocity change, differs from:
  - a trip or slip fall: one ground impact, mostly vertical, under about 1 s;
  - a phone drop: about 0 g free fall, then a single sharp spike, with no gait before it and the device separated from the body.
- A phone in a pocket or bag may separate from the body and register its own impacts. That makes the signature noisier than PMHS instrumentation.
- GPS and speed may show a displacement of several metres or more (the throw distance), which a normal fall would not produce.

### Gaps
- I could not retrieve peak resultant accelerations in g for pelvis, T1, T8 or head in full-scale PMHS or dummy pedestrian tests. The tables exist in Kerrigan et al. ([Kinematic Corridors](https://www.researchgate.net/publication/236211033_Kinematic_Corridors_for_PMHS_Tested_in_FUll0Scale_Pedestrian_Impact_Tests)) and in the generic-sedan-buck PMHS paper ([Whole-body response](https://www.researchgate.net/publication/286994054_Whole-body_Response_for_Pedestrian_Impact_with_a_Generic_Sedan_Buck)), but the full text was not accessible or parseable.
- I found no published ground-impact peak g values for pedestrians.
- I found no data on what a pocketed phone, as opposed to the body, records during a pedestrian strike.

## Q3. Do consumer products (Apple, Google, Samsung, Garmin, apps) detect pedestrians being hit?

### Takeaway
No major product is marketed to detect a pedestrian being struck by a vehicle. Apple Crash Detection is explicitly for passenger-car occupants. Fall detection on watches (Apple) and incident detection (Garmin, for outdoor walk/run/bike) can fire after a vehicle strike as a side effect, and there are anecdotal cases of this.

### Cited Findings
- **Apple Crash Detection** covers severe front, side and rear-end crashes and rollovers in passenger vehicles (sedans, minivans, SUVs, pickups). It uses device sensor data including the microphone, and Apple says it "cannot detect all car crashes." The support page does not mention pedestrians, cyclists or motorcycles. — [Apple Support 104959](https://support.apple.com/en-us/104959)
- Crash Detection requires iPhone 14 or later with iOS 16 or later, or Apple Watch Series 8, SE (2nd gen) or Ultra with watchOS 9 or later. — [Wikipedia: Crash Detection](https://en.wikipedia.org/wiki/Crash_Detection)
- **Apple Watch Fall Detection** (SE / Series 4 or later) detects a "hard fall." If the user is moving, it waits for a response rather than calling automatically, and it "cannot detect all falls." — [Apple Legal – Safety Features](https://www.apple.com/legal/privacy/data/en/safety-features/)
- **Anecdotal evidence:**
  - A San Francisco cyclist was hit by a car and left unconscious. His Apple Watch fall detection called 911 and paramedics arrived within minutes. The article notes Apple Watch has no bicycle or car crash mode. — [iDrop News](https://www.idropnews.com/news/apple-watch-fall-detection-sends-paramedics-in-just-minutes-after-cyclist-gets-hit-by-car/126765/)
  - A 2021 driver collision triggered fall detection. — [AppleInsider](https://appleinsider.com/articles/21/08/20/apple-watch-fall-detection-aids-in-rescue-of-car-crash-victim)
- **Garmin Incident Detection:**
  - One Forerunner manual limits it to outdoor walk, run and bike activities; other manuals say "certain outdoor activities," depending on the device.
  - It needs a paired phone with data and notifies the user's own emergency contacts, not emergency services, after a 15 s countdown (30 s on some models).
  - Garmin calls it a supplemental feature.
  — [Garmin manual](https://www8.garmin.com/manuals/webhelp/GUID-5D183A14-BB43-4A9B-B441-5F824214CE40/EN-US/GUID-CE8456FF-55FB-4BF4-BCAB-9871D9EB5D18.html); [Forerunner 165 manual](https://www8.garmin.com/manuals-apac/webhelp/forerunner165series/EN-SG/GUID-0C7D90D2-FA6A-4690-A62D-1199948CDD17-3931.html)

### Inferences
- In practice, a pedestrian struck while walking or running is covered only incidentally:
  - Apple fall detection, which is always on for users who enable it.
  - Garmin incident detection, but only during an active recorded walk, run or bike activity.
- Apple's car Crash Detection probably does not apply. It is tuned to vehicle-occupant signals such as speed context, cabin pressure or noise. That is my inference from its "passenger vehicle" scope, not a stated exclusion.
- No product discloses detection rates for vehicle-strike events.

### Gaps
- I did not verify:
  - whether Google Pixel Car Crash Detection or Samsung Galaxy Watch fall detection apply to pedestrians;
  - details for bSafe, Noonlight, Strava Beacon, Specialized ANGi or Livall helmets.
  Note: Strava Beacon is location sharing only. ANGi and Livall are helmet crash sensors for cyclists. These need confirming from the vendors' own pages.
- Apple's internal crash and fall detection datasets and thresholds are not public.

## Q4. Related VRU work: cyclist and e-scooter crash detection (studies, datasets, accuracy)

### Takeaway
Cyclist and e-scooter crash detection is far more developed than pedestrian work, but most studies use simulated falls, crash-test dummies or near-miss manoeuvres, not real crashes. The best-validated is a probabilistic IMU classifier tested on crash-dummy and crash-car tests, reporting about 97% sensitivity and 99.6% specificity. Phone-only approaches report about 85–95% accuracy on small samples.

### Cited Findings
- **Probabilistic online bicycle crash classification** (Accid. Anal. Prev. 2021) used crash-dummy and crash-car tests and reports 96.8% sensitivity, 99.6% specificity and 99.5% accuracy (IMU-based). — [ScienceDirect S0001457521003420](https://www.sciencedirect.com/science/article/abs/pii/S0001457521003420)
- **Smartphone detection of critical cycling manoeuvres** (Sensors 2026) is a two-stage method: kinematic thresholds pick candidates, then an SVM classifies them. With 10 riders in Germany, mean PR-AUC is 0.954 and F1 0.924 for hard braking, and PR-AUC 0.884 and F1 0.876 for abrupt swerving. It was evaluated offline and does not detect crashes. — [PMC13517138](https://pmc.ncbi.nlm.nih.gov/articles/PMC13517138/); [doi 10.3390/s26165138](https://doi.org/10.3390/s26165138)
- **Naturalistic e-scooter / e-bike detection (TRID record):**
  - Threshold rules on acceleration, jerk, rotation and post-event vehicle motion were calibrated on a test track.
  - They were then applied to 119 participants, over 26,000 km and 1,600 h, with accelerometer, gyroscope, GPS and video.
  - Video review confirmed about 75% of hard-braking flags and about 64% of sharp-turn flags, and found 91% of strong-jolt flags were road infrastructure features.
  - Several reported and previously unreported collisions with other road users were confirmed.
  — [TRID 2743022](https://trid.trb.org/View/2743022); [PubMed 42546572](https://pubmed.ncbi.nlm.nih.gov/42546572/)
- **Chalmers naturalistic rental e-scooter study:** 61 safety-critical events (19 crashes, 42 near-crashes). Phone use, single-handed riding and pack riding raised crash risk. — [Chalmers 543799](https://research.chalmers.se/en/publication/543799)
- **Edge-AI scooter accident detection:** an MPU6050 and ESP32 with a sequential neural net, trained on simulated crash, fall and normal-riding data. It runs on dedicated hardware, not a phone. — [Springer chapter](https://link.springer.com/chapter/10.1007/978-3-032-06691-6_25)
- **Other cycling results:**
  - A MARG-sensor cycling fall detector (24 features, PCA, SVM) reports 95.2% accuracy.
  - A smartphone deep-learning bike crash app reports 87.8% accuracy.
  - BikeSafe behaviour monitoring reports 86.8% accuracy with 12 participants.
  These figures come via aggregator pages, so check the originals. — [Accident Detection System for Bicycle Riders](https://www.researchgate.net/publication/344618254_Accident_Detection_System_for_Bicycle_Riders); [Smart System to Detect Bike Accidents](https://www.researchgate.net/publication/377089014_A_Smart_System_to_Detect_Bike_Accidents_and_Reduce_Casualties); [BikeSafe](https://www.academia.edu/80188027/BikeSafe_bicycle_behavior_monitoring_via_smartphones)
- A motorcycle collision-detection ML study exists, also on dedicated sensors. — [arXiv 2403.09491](https://arxiv.org/pdf/2403.09491); [arXiv 2403.19085](https://arxiv.org/html/2403.19085v1)
- Smartphone VRU transport-mode detection (pedestrian vs. cyclist, etc.) uses recurrence quantification analysis. It is useful as context-gating before crash detection. — [arXiv 2006.06941](https://arxiv.org/pdf/2006.06941)
- A patent on phone crash detection converts 3-axis accelerometer data to a delay-domain image and applies a Hough transform. It is used as an on-device false-positive filter before a cloud check. — [USPTO 10728378](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10728378)

### Inferences
- **Approaches that carry over to pedestrians:**
  - two-stage designs (a kinematic threshold trigger, then an ML false-positive filter);
  - post-event motion checks (has the person or vehicle stopped moving?);
  - context gating (detect "walking" mode first).
- Crash-test dummies and staged crash rigs, as in the cycling studies, are a feasible way to build pedestrian training data. No pedestrian-specific equivalent was found.

### Gaps
- I could not access sample sizes or the number of real crashes in the 2021 probabilistic study.
- The 87.8% and 99% bike-app claims are weakly documented.

## Q5. Datasets with pedestrian impacts or simulated collisions recorded on phones or wearables

### Takeaway
I found no public dataset of pedestrian–vehicle impacts recorded on a phone or wearable. The closest resources are cycling and micromobility datasets and fall-detection datasets (simulated falls and ADLs).

### Cited Findings
- **Kaggle "Cyclist Accident Prevention Dataset":** handlebar-mounted Android phone recording accelerometer, gyroscope, magnetometer and GPS. It is aimed at accident prevention and has few labelled crash events. — [Kaggle](https://www.kaggle.com/datasets/shashwatwork/cyclist-accident-prevention-dataset)
- The naturalistic e-scooter/e-bike dataset (119 participants, IMU, GPS, video) contains some confirmed collisions. Public availability was not confirmed. — [TRID 2743022](https://trid.trb.org/View/2743022)
- The 2026 Sensors cycling manoeuvre dataset (10 riders) does not appear to be public. — [PMC13517138](https://pmc.ncbi.nlm.nih.gov/articles/PMC13517138/)

### Inferences
- A pedestrian-strike detector would currently have to rely on proxies:
  - fall datasets (e.g. SisFall, MobiFall, FallAllD, from general knowledge; not verified in this session);
  - PMHS or dummy kinematic corridors used to synthesise signals;
  - human-body-model simulation (e.g. SAFER HBM, MADYMO) to generate virtual IMU traces at phone or watch locations;
  - staged dummy impacts with a phone attached.

### Gaps
- I found no verified public dataset containing real or staged vehicle-to-pedestrian impacts with phone or wearable IMU data. This is a clear gap and research opportunity.
- I did not verify which fall datasets include "collision" or "pushed" classes.
