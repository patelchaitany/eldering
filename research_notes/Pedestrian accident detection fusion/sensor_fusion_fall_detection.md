# Multi-Sensor Fusion for Smartphone Fall/Accident Detection (Walking) and Context-Aware Walking/Cycling/Driving Detection

Scope note: ~22 tool calls (searches, fetches, PDF text extraction). Primary sources read in full or near-full: Tsinganos & Skodras 2018 (Sensors), Casilari et al. 2016 (PLOS ONE), the Northwestern real-life smartphone study (JNER 2021), Luo et al. 2022 P2Est (ACM HEALTH, barometer). Other items come from search-result abstracts/snippets and are flagged where confidence is lower.

## 1. Fusion levels used in the literature and which gives the best accuracy / false-positive trade-off

### Takeaway
Smartphone fall detectors mostly use threshold-based (TBA) or a "cheap threshold trigger -> ML classifier" cascade, with feature-level fusion of accelerometer + gyroscope as the most common fusion form. There is no head-to-head smartphone benchmark showing one fusion level is best. Hand-tuned multi-sensor threshold fusion often *loses* to a single-sensor ML classifier. Decision-level (late) fusion is favoured for robustness and low false-positive rates, and learned (deep) fusion wins when enough data exists.

### Cited Findings
- Fusion-level definitions used in smartphone fall-detection work: *feature-level fusion* selects/transforms extracted features (possibly into higher/lower-dimensional spaces) before classification. *Decision-level fusion* combines per-sensor decisions to improve event-detection accuracy. The same paper discusses Bayesian inference, Dempster-Shafer and fuzzy logic as fusion tools. — [Tsinganos & Skodras 2018, Sensors 18(2):592](https://www.mdpi.com/1424-8220/18/2/592)
- Head-to-head comparison (10-fold CV) of three published acc+gyro fusion algorithms against a single-sensor (accelerometer-only) threshold-state-machine + kNN on MobiAct, DLR and UMAFall:
  - Alg 1 (Casilari: complementary-filter orientation, direct data fusion): MobiAct Se 0.751 / Sp 0.686 / F1 0.581.
  - Alg 2 (Figueiredo: feature-level, 5 features over a 2 s window, each thresholded): MobiAct 0.990 / 0.928 / 0.908; UMAFall 0.936 / 0.971 / 0.951.
  - Alg 3 (Ando: correlation with fall templates, gyro refinement): MobiAct 0.707 / 0.776 / 0.628.
  - Alg 4 (single-sensor kNN): MobiAct 0.918 / 0.977 / 0.929; DLR 0.988 / 1.000 / 0.994; UMAFall 0.979 / 0.959 / 0.967.
  - Friedman tests found significant differences (p<0.05). The authors conclude fusion results are dataset-dependent and "a machine learning strategy should be preferred" over complex multi-parameter threshold fusion. Sensors were sampled at 50 Hz. — [Tsinganos & Skodras 2018 (PMC)](https://pmc.ncbi.nlm.nih.gov/articles/PMC5856082/)
- Multi-position decision fusion (phone in pocket at 200 Hz + 4 SensorTags at 20 Hz on wrist/waist/chest/ankle; all 31 combinations; AND-rule: every sensor must say "fall"):
  - Wrist was the best single position.
  - Adding more than two positions *reduced sensitivity* without enough specificity gain. The smartphone and ankle positions did not improve detection.
  - Best combo (wrist + waist/chest, Fall Index) reached Se just above 0.6 at Sp >= 0.95.
  - Hopping caused 46–55% false "fall" labels with all 5 sensors.
  - Simple thresholds (SMV, Fall Index) beat the more complex PerFallD/iFall. Only accelerometer data was used, so gyro/magnetometer value was not assessed.
  - The authors recommend reporting sensitivity at a fixed minimum specificity because falls are rare. — [Casilari et al. 2016, PLOS ONE](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0168069)
- Early versus late fusion: early fusion risks amplifying noise. Late fusion processes each source independently, isolates faults and resists single-sensor failure. "Decision-level fusion is more robust than early fusion," though both beat single modality. Their waist IMU + camera late-fusion system: F1 97.3% with 3.6% false-positive rate, versus 11.3% (IMU-only) and 8.9% (vision-only) FPR, on a custom simulated-fall dataset. — [Bimodal late fusion, 2025 (PMC12526565)](https://pmc.ncbi.nlm.nih.gov/articles/PMC12526565/)
- Counterpoint for learned fusion: an early-fusion CNN-LSTM with attention over accelerometer, gyroscope, insole pressure and PPG reached 94.2% accuracy and AUC 0.967. It beat single-modality CNN/LSTM baselines and a late-fusion variant, and the ablation without attention reached 91.0%. The authors argue early fusion lets attention re-weight modalities per activity. Note: this is fall-*risk* prediction, not detection. — [Fall-risk multimodal fusion (PMC13527089)](https://pmc.ncbi.nlm.nih.gov/articles/PMC13527089/)
- A multi-modal CNN-LSTM with multi-head attention and focal loss fuses tri-axial accelerometer, gyroscope and physiological signals, with transfer from UCI HAR to SisFall. — [arXiv 2603.22313](https://arxiv.org/html/2603.22313)
- A CNN-LSTM on SisFall at 20 Hz reportedly gave the best balance (accuracy 98.9%, Se 96.7%, Sp 99.6%), and 10–20 Hz sampling was sufficient, which reduces energy. This comes from a search summary. The likely source is the accelerometer sampling-rate study; verify before quoting. — [Impact of accelerometer sampling rate on ML/DL fall detection (PMC12788221)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12788221/)
- Igual et al. 2015 found that dataset choice strongly affects detector performance, that performance "decreases dramatically" when testing on a dataset other than the training one, and that algorithms were less sensitive to sampling frequency or acceleration range. — [Igual et al. 2015, Med Eng Phys](https://zaguan.unizar.es/record/48442)
- Most smartphone fall-detection systems use threshold-based algorithms (TBA). Others use ML or hybrid ML + threshold. Common sensors are tri-axial accelerometer, magnetometer, gyroscope and camera. — [Review on fall detection using smartphone sensors, IIETA ISI 2019](https://iieta.org/journals/isi/paper/10.18280/isi.240602)
- An Android-specific review notes "complete lack of a reference framework to validate and compare the proposals." — [Casilari, Luque & Morón 2015, Sensors 15:17827](https://doi.org/10.3390/s150817827)
- Seminal smartphone survey on challenges and open issues: [Habib et al. 2014, Sensors (PMC4029687)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC4029687/)
- A 2024 Information Fusion survey covers data-fusion approaches for fall detection (wearables, smartphones, cameras). Only the abstract was visible (full text returned HTTP 403), so its conclusions on Kalman and Dempster-Shafer are not captured here. — [Survey on data fusion approaches for fall-detection, Information Fusion 2024](https://www.sciencedirect.com/science/article/pii/S1566253524004743)
- Data-level (signal) fusion in practice: the complementary filter fusing accelerometer + gyroscope for orientation/tilt (Casilari algorithm in Tsinganos 2018). The Android rotation-vector sensor (a fused virtual sensor) is used for body tilt in P2Est. — [Tsinganos 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC5856082/); [Luo et al. 2022, P2Est, ACM Trans. Comput. Healthcare 3(3):27](https://doi.org/10.1145/3478027)

### Inferences
- For an Android app, a pragmatic stack is as follows:
  - Data-level: use the OS-fused sensors (`TYPE_ROTATION_VECTOR` / `TYPE_GAME_ROTATION_VECTOR` for orientation, `TYPE_LINEAR_ACCELERATION`) rather than a hand-rolled complementary/Kalman filter.
  - Feature-level: build an acc + gyro (+ baro delta) feature vector or a small CNN/LSTM over 2–5 s windows.
  - Decision-level: veto/confirm stages for post-fall inactivity, barometric height drop, impact audio and context.
- A cascade (threshold trigger -> classifier -> confirmation stages) is the dominant false-positive-control pattern.
- An AND-of-all-sensors decision rule hurts sensitivity (Casilari 2016). Prefer weighted/learned combination or "k-of-n" voting, and evaluate sensitivity at fixed specificity.
- Kalman/Dempster-Shafer appear mostly as theoretical options. I found no smartphone study showing they beat simpler learned fusion.

### Gaps
- No smartphone-only benchmark directly compares data-, feature- and decision-level fusion (and Kalman, D-S, Bayesian) on the same public dataset with real-world FP rates.
- I could not read the full Information Fusion 2024 survey (403). Its ranking of fusion methods is unknown.
- Transformer-based multi-branch phone-sensor fusion results were not captured in this pass.

## 2. Role of each sensor (barometer, gyroscope, inactivity, microphone, GPS, proximity/light)

### Takeaway
The accelerometer is the trigger (impact peak, free-fall). The gyroscope / rotation vector gives orientation change. The barometer gives height drop: it can see slow falls that accelerometer integration misses, but it drifts with weather, temperature and device. Post-fall inactivity is the main false-positive filter. The microphone is a post-trigger confirmer. GPS and activity recognition provide context, and proximity/light provide on-body / in-pocket gating for battery and false-positive control.

### Cited Findings
- **Barometer (P2Est):**
  - Linear acceleration detects height change only in rapid falls and accumulates error when integrated. The barometer measured altitude change in both rapid and *slow* falls without cumulative error.
  - Dropping a phone 50 times from a 1.2 m table gave a measured height of mean 1.21 m and SD 0.13 m.
  - Indoor-measured altitude drifted from 209.27 m to 191.64 m over one day (9 AM–8 PM) due to weather and temperature. Indoor/outdoor readings differed by 1.75 m at 8 PM.
  - Three co-located phones read mean altitudes of 226.88 / 235.37 / 231.65 m, so absolute pressure is device-specific.
  - Fix: calibrate the device-to-floor pressure difference while the user is walking (height assumed constant). This reached decimeter-level accuracy (phone on floor estimated at ~0.1 m).
  - Prototype: Pixel XL (BMI160 IMU, BMP280 barometer), rotation vector at 50 Hz (SENSOR_DELAY_GAME), other sensors at SENSOR_DELAY_NORMAL. Barometer characterisation was done at 5 Hz.
  - Failure modes: going downstairs produced pressure-rise false positives. With a height-ratio threshold of 0.2, falling vs lying on a couch gave FPR 0 but FNR 0.3, because a chest-pocket phone on a 0.43 m couch is close to a fallen body's height. — [Luo et al. 2022, P2Est (ACM)](https://doi.org/10.1145/3478027); [PDF](https://rtcl.eecs.umich.edu/rtclweb/assets/publications/2022/tch22-luo.pdf)
- **Barometer (patent):** a fall-detection pendant patent describes an acceleration-spike trigger followed by a height-drop check comparing pressure before vs after the spike. The example threshold is 20–30 inches (~0.5–0.76 m), and the raw pressure delta can be thresholded directly without converting to elevation. Exactly which of these USPTO results contains this passage was not pinned down. — [USPTO 12676063](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12676063); [USPTO 12367757](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12367757); [USPTO 12039850 (multiple sensors incl. barometric)](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12039850)
- **Barometer (FallAllD):** FallAllD is the public dataset found that includes a barometer (10 Hz) alongside acc/gyro/mag on neck/waist/wrist. — [FallAllD (ResearchGate)](https://www.researchgate.net/publication/343780462_FallAllD_An_Open_Dataset_of_Human_Falls_and_Activities_of_Daily_Living_for_Classical_and_Deep_Learning_Applications)
- **Accelerometer + gyroscope (real life):**
  - The Northwestern system triggers on a 2 g peak, then runs an elastic-net logistic regression on 40 features (20 acc, 20 gyro) over 5 s windows, with decision threshold 0.908.
  - 6 of 10 missed real falls never crossed 2 g, so the trigger threshold is the main sensitivity bottleneck. 3 of the misses were falls to one knee.
  - 42.2% of false alarms were stumbles. — [Mubarak/Northwestern, JNER 2021 (PMC8353784)](https://pmc.ncbi.nlm.nih.gov/articles/PMC8353784/)
- **Post-fall inactivity / mobility:** two-stage designs check "capacity to act" after a fall. One 2024 Android system uses an IMU model first, then WiFi CSI to assess post-fall mobility, and alerts only if the user cannot move. Its CSI CNN reached 99% accuracy. Literature cited inside P2Est lists vertical velocity, heart rate and immobility time as false-positive reducers. — [Wang et al. 2024, arXiv 2412.09980](https://arxiv.org/pdf/2412.09980); [Luo et al. 2022](https://doi.org/10.1145/3478027)
- **Microphone:**
  - A patent describes flagging a suspected fall from acceleration + orientation change, then confirming from stored audio, with optional cloud re-check and a voice call. — [US9648478](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9648478); [US12072348 (Google Patents)](https://patents.google.com/patent/US12072348)
  - An Android accelerometer + microphone app (18 volunteers) reported Se 96% / Sp 77% in quiet and noisy settings. This is weak-quality evidence; specificity is low. — [ISC record](https://search.isc.ac/Inventory/8/947527.htm)
  - A Fraunhofer Xperia X10 prototype also combined accelerometer and microphone. — [Fraunhofer](https://publica.fraunhofer.de/handle/publica/279005)
- **Proximity, microphone, GPS together:** Biancone et al. 2021 (CNR) fuse accelerometer, gyroscope, proximity, microphone and GPS with a threshold-based algorithm, with privacy-limited first-aid requests. — [CNR IRIS](https://iris.cnr.it/handle/20.500.14243/411599)
- **Proximity for battery gating:** the open-source BeSafeBox Android app sleeps its model when the proximity sensor indicates the phone is out of the pocket, and disables detection on low battery. — [BeSafeBox GitHub](https://github.com/Foxpace/BeSafeBox_Android_app)
- **GPS / context:** the Northwestern system logged GPS location and speed for 5 min pre-fall and Google Activity Recognition API output for 60 s pre-fall. Pre-fall activities: tilting 41%, still 26%, on foot 11%, bicycle 4%, unknown 18%. — [PMC8353784](https://pmc.ncbi.nlm.nih.gov/articles/PMC8353784/)
- **Commercial crash detection (vehicle):**
  - Apple fuses a high-g accelerometer (up to 256 g), gyroscope, barometer (cabin pressure spike from airbag deployment, weakened with windows open), microphone (crash sound peaks), GPS and CarPlay/Bluetooth for in-car context. Apple describes a "dynamic algorithm" with no single rule. — [TechCrunch](https://techcrunch.com/2022/09/07/apple-watch-series-8-has-new-sensors-that-can-detect-when-youre-in-a-car-crash); [9to5Mac](https://9to5mac.com/2022/10/10/how-crash-detection-works/)
  - Pixel Car Crash Detection requires location, physical-activity (activity recognition) and microphone permissions. It is trained on real and simulated crashes and compares accelerometer, GPS and microphone data. — [9to5Google](https://9to5google.com/2022/09/07/google-pixel-car-crash-detection/); [Google Store](https://store.google.com/intl/en_uk/ideas/articles/car-crash-detection)

### Inferences
- Barometer use should be *relative and short-window*: compare the median pressure in ~1–2 s before the trigger against ~2–5 s after it. Never use absolute altitude. A ~1 m body drop is ~12 Pa, comparable to the weather drift seen over hours, so only short windows work. Use the walking-phase baseline (P2Est idea).
- Expect stair and elevator false positives from the barometer. Gate it with step detection / activity state.
- Phone height in a pocket (~0.5–1 m) means a "fall" drop can be only ~0.3–0.8 m. Thresholds should be lower than body height, and couch/bed lying is a confound.
- The microphone should be duty-cycled: buffer only post-trigger (or a short ring buffer) for privacy and battery.

### Gaps
- No quantitative ablation found that shows incremental FP reduction from adding barometer or microphone to acc + gyro on a smartphone dataset.
- No magnetometer-specific contribution was quantified. Casilari 2016 used acc only, and Tsinganos used acc + gyro.
- Light-sensor use is undocumented beyond in-pocket heuristics.

## 3. Benchmark datasets with phone (or phone-like) sensors: sensors, sampling rates, licensing

### Takeaway
Only MobiAct/MobiFall, UMAFall (phone part) and UniMiB SHAR are true smartphone datasets. SisFall, KFall, FallAllD and WEDA-Fall use custom IMUs or smartwatches. FallAllD is the only one found with a barometer. Licensing ranges from "on request, non-commercial, with a usage agreement" (MobiAct) to openly downloadable (UniMiB, WEDA-Fall on GitHub). No dataset found includes phone microphone audio or GPS during falls.

### Cited Findings
- **MobiFall/MobiAct:**
  - Smartphone in the front trouser pocket, recording accelerometer, gyroscope and orientation.
  - MobiAct: 57 subjects, 4 fall types, 9 ADLs. The extended version has 66 subjects, 4 falls, 12 ADLs and a daily-living scenario, with more than 3,200 trials.
  - Sampling is reported as 20 Hz in one source; Tsinganos et al. processed it at 50 Hz. The native rate is likely higher and resampled, so treat as uncertain.
  - Access: available on request (bmi@hmu.gr) for non-commercial, research and educational use after signing a database usage agreement. — [BMI Lab, HMU](https://bmi.hmu.gr/the-mobifall-and-mobiact-datasets-2/); [MobiAct paper](https://www.researchgate.net/publication/302973506_The_MobiAct_Dataset_Recognition_of_Activities_of_Daily_Living_using_Smartphones); [Tsinganos 2018](https://pmc.ncbi.nlm.nih.gov/articles/PMC5856082/)
- **UMAFall:** smartphone accelerometer at 200 Hz plus TI SensorTags (acc, gyro, mag) at 20 Hz. One comparison table lists the phone at 100 Hz (conflict). A precursor dataset had 530 samples from 17 subjects and is public. — [UMAFall (ResearchGate)](https://www.researchgate.net/publication/318385531_UMAFall_A_Multisensor_Dataset_for_the_Research_on_Automatic_Fall_Detection); [Casilari 2018, Sensors 18:1155](https://doi.org/10.3390/s18041155); [PLOS ONE 2016](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0168069)
- **UniMiB SHAR:** Samsung Galaxy Nexus I9250 accelerometer only, 50 Hz (Android cap), 3 s windows, 11,771 samples from 30 subjects (ADLs + falls), downloadable from the project site. The authors note Android does not guarantee the requested sampling rate and it fluctuates. — [UniMiB SHAR, arXiv 1611.07688](https://arxiv.org/html/1611.07688v5)
- **SisFall:**
  - Self-developed waist IMU (accelerometer + gyroscope), 200 Hz.
  - One study used a subset of 1,575 falls / 1,659 ADL trials. Full-dataset counts were not verified in this pass.
  - No barometer. — [Frontiers / dataset comparison search results](https://www.frontiersin.org/articles/692865); [arXiv 2012.03426](https://arxiv.org/pdf/2012.03426)
- **FallAllD:** neck, waist and wrist units, 15 subjects, 26,420 files. Accelerometer 238 Hz (±8 g), gyroscope 238 Hz (±2000 dps), magnetometer 80 Hz, barometer 10 Hz. — [FallAllD](https://www.researchgate.net/publication/343780462_FallAllD_An_Open_Dataset_of_Human_Falls_and_Activities_of_Daily_Living_for_Classical_and_Deep_Learning_Applications)
- **KFall:** low-back IMU (acc, gyro, orientation), 100 Hz, 32 Korean participants, 21 ADLs + 15 simulated falls. Designed for *pre-impact* fall detection; downsampling is supported. — [KFall](https://www.researchgate.net/publication/353293602_A_Large-Scale_Open_Motion_Dataset_KFall_and_Benchmark_Algorithms_for_Detecting_Pre-impact_Fall_of_the_Elderly_Using_Wearable_Inertial_Sensors)
- **WEDA-Fall:** Fitbit Sense smartwatch on the wrist (accelerometer + gyroscope), 50 Hz plus derived 40/25/10/5 Hz versions, 25 participants (14 young, 11 aged over 80), 8 fall types, 11 ADLs, hosted on GitHub. Fall/ADL counts conflict across sources. — [WEDA-FALL GitHub](https://github.com/joaojtmarques/WEDA-FALL); [Wrist-based fall detection, Sensors 24(5):1679](https://www.mdpi.com/1424-8220/24/5/1679)
- Dataset reviews: Casilari et al. 2017 appraised 12 public repositories. They found no common benchmarking procedure, with wide variation in duration, fall/ADL types, subjects and positions. — [Analysis of Public Datasets for Wearable FDS (PMC5539544)](https://pmc.ncbi.nlm.nih.gov/articles/PMC5539544/)
- Real-fall plus long-term-ADL cross-dataset evaluation (wearables) shows "remarkable degradation" in Se, Sp and hourly false-alarm rate versus lab data. — [Measurement 2024 (S0263224124008777)](https://www.sciencedirect.com/science/article/pii/S0263224124008777)
- Other sources: a combined-fall-dataset aggregation repo — [GitHub Vani-Fula](https://github.com/Vani-Fula/Combined-fall-dataset); a Kaggle smartphone fall dataset — [Kaggle](https://www.kaggle.com/datasets/saadmansakib/smartphone-human-fall-dataset). Neither was evaluated.

### Inferences
- For a phone-in-pocket app:
  - Pretrain on MobiAct + UMAFall phone + UniMiB (thigh/pocket) data.
  - Augment with SisFall/KFall resampled and rotated to mimic pocket placement.
  - Use FallAllD for barometer feature design.
- Resample everything to a common rate (~50 Hz). Evidence suggests 20–50 Hz is adequate, and it suits Android's jittery delivery.
- MobiAct's non-commercial agreement may block a commercial app. Licensing must be checked per dataset.

### Gaps
- Exact licenses for SisFall, UMAFall, FallAllD, KFall and UniMiB were not confirmed in this pass.
- MobiAct native sampling rate is unconfirmed (sources conflict).
- No public dataset found contains phone barometer + microphone + GPS during real or simulated pedestrian falls. Nor did I find a public phone dataset spanning cycling crashes.

## 4. Reported accuracy and real-world (non-lab) performance; lab-to-real gap

### Takeaway
Lab results commonly exceed 95% Se/Sp, but the best-documented prospective smartphone deployment showed Se dropping from ~95% to 73%, with precision only 37.5%. That is about one false alarm per 46 user-days. Real-world false alarms/day are rarely reported, and the field's numbers are not comparable across studies.

### Cited Findings
- **Northwestern prospective study (Samsung Galaxy S5, pocket/waist pouch, acc + gyro interpolated to 50 Hz):**
  - Lab, 17 participants, LOSO: AUROC 0.995, accuracy 0.959, Se 0.954, Sp 0.984.
  - Real life: 23 high-fall-risk participants over 2,070 days (~14.9 M 5-s windows). 27 of 37 falls detected (Se 73.0%), Sp >99.9%, precision 37.5%, F1 0.495, about one false alarm per 46 days.
  - Table 3 lists a daily false-alarm rate of 0.5, which conflicts with the text and is probably an error.
  - Lab metrics were per-window while real-life metrics were per-event, so they are not directly comparable.
  - Some missed falls happened because the phone was not carried. — [JNER 2021 (PMC8353784)](https://pmc.ncbi.nlm.nih.gov/articles/PMC8353784/); [Springer](https://link.springer.com/article/10.1186/s12984-021-00918-z)
- A threshold-based Android system with personalised thresholds still produced more than one false positive per day (4 subjects, 3 days). The review also notes that false alarms/day in a real environment are rarely evaluated. — [Casilari et al. 2015, Sensors](https://doi.org/10.3390/s150817827)
- A smartphone novelty-detection study (acceleration patterns) reported real-scenario false positives/day. The exact figures were not extracted. — [PLOS ONE 2014](https://journals.plos.org/plosone/article?id=10.1371%2Fjournal.pone.0094811)
- Cross-dataset training/testing causes dramatic performance drops. — [Igual et al. 2015](https://zaguan.unizar.es/record/48442)
- Real falls plus long-term daily-life data cause remarkable degradation in Se, Sp and hourly false-alarm rate for wearable detectors. — [Measurement 2024](https://www.sciencedirect.com/science/article/pii/S0263224124008777)
- Consumer-scale false-alarm evidence: iPhone 14 Crash Detection triggered by skiing produced 134 false emergency calls (>14% of call volume) in Japan's Hida region between 16 Dec 2022 and 23 Jan 2023. — [Wikipedia: Crash Detection](https://en.wikipedia.org/wiki/Crash_Detection)
- Phone-handling actions (e.g., tossing the phone onto a sofa) still cause false positives in a 2024 TFLite IMU system. — [arXiv 2412.09980](https://arxiv.org/pdf/2412.09980)

### Inferences
- Design target: <=1 false alarm per user per month while keeping Se >= 70–80% on real falls. Use a user-cancellable countdown, because precision will be low.
- The 2 g trigger was the main miss cause, so a lower trigger (~1.6–1.8 g) plus a stronger second-stage classifier and confirmation sensors is likely better.
- Report per-event metrics and false alarms per day/hour, not per-window accuracy.

### Gaps
- Very few real-world smartphone deployments exist. No multi-sensor (baro/mic) smartphone system with real-world FA/day was found.
- Kangas et al. long-term real-fall work (wearable) was not retrieved.

## 5. Context-aware / multi-mode detection (walking / cycling / driving) and unified frameworks

### Takeaway
No peer-reviewed unified framework was found that first classifies mode (walk / run / cycle / vehicle) and then applies mode-specific fall, bike-crash and car-crash detectors on a phone. The building blocks exist: hierarchical transport-mode detection, speed-gated car-crash detection, multi-criteria cyclist crash detection, and commercial systems that use activity recognition as context (Pixel). This is the implicit architecture commercial products use.

### Cited Findings
- **Transport-mode detection (the context layer):**
  - Hierarchical smartphone context detection places a human-vs-vehicle classifier at the top level to separate motorised from non-motorised motion, then finer classifiers. — [arXiv 2005.07539](https://arxiv.org/pdf/2005.07539)
  - VADS: an accelerometer-only (for energy) vehicle-mode module separates car/bus/motorbike from walking/bike, and a driving module detects stop/straight/left/right. — [VADS (PMC5948751)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5948751/)
  - T2Trans (TCN) recognises 8 modes (still, walk, run, cycle, car, bus, subway, train) with macro-F1 86.42% on SHL and 88.37% on HTC. — [PMC9459749](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9459749/)
  - Feature-pyramid BiLSTM on acc/gyro/mag only: 95% accuracy, F1 94% on 8 SHL modes. — [ScienceDirect S2590198224001672](https://www.sciencedirect.com/science/article/pii/S2590198224001672)
  - Other work: an optimised LSTM on multimodal data — [PMC8622795](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8622795/); a review — [PMC11598250](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11598250/); accelerometer-only TMD with >20% improvement mostly on motorised modes — [Hemminki et al. PDF](https://cse.buffalo.edu/~lusu/cse726/papers/Accelerometer-Based%20Transportation%20Mode%20Detection%20on%20Smartphones.pdf)
- **Car crash (vehicle mode):**
  - WreckWatch uses GPS speed and starts accelerometer crash analysis only above 15 mph, filtering phone-drop shocks. A critique notes it cannot detect low-speed accidents. — [WreckWatch (Vanderbilt)](http://www.dre.vanderbilt.edu/~jules/wreckwatchj.pdf)
  - Phone accelerometer thresholds alone cannot give credible crash detection. — [Effective Car Collision Detection with Mobile Phone Only, ICCS 2021](https://www.iccs-meeting.org/archive/iccs2021/papers/127470294.pdf)
  - An Android crash app detected 100% of test crashes but had a high false-positive rate. — [Car crash detection on smartphones, ACM](https://dl.acm.org/doi/epdf/10.1145/2790044.2790049)
  - A survey of smartphone accident detection: [IJACSA 9(9) 2018](https://thesai.org/Downloads/Volume9No9/Paper_52-A_Survey_on_Smartphone_based_Accident.pdf)
  - Patent on crash detection from smartphone sensor data: [USPTO 10728378](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10728378)
- **Cycling:**
  - A Chalmers naturalistic-cycling crash algorithm requires acceleration, rotation and speed criteria to all be met. It detected all 6 crashes with zero false alarms on normal cycling data (tiny sample). — [Chalmers](https://publications.lib.chalmers.se/records/fulltext/211381/local_211381.pdf)
  - A 2026 two-stage smartphone framework uses thresholds for hard braking / abrupt swerving followed by ML filtering. Stage 2 cut false positives by 20.0–83.3% (braking) and 87.2–98.2% (swerving). SVM PR-AUC was 0.954 (braking) and 0.884 (swerving). Lifting or handling the bike is a likely false-positive source. Offline proof of concept. — [Sensors 2026, doi 10.3390/s26165138](https://doi.org/10.3390/s26165138); [PMC13517138](https://pmc.ncbi.nlm.nih.gov/articles/PMC13517138/)
- **Commercial context-aware systems:** Pixel Car Crash Detection needs the activity-recognition permission (in-vehicle context) plus location and microphone. Apple uses CarPlay/Bluetooth and GPS as in-car context. — [9to5Google](https://9to5google.com/2022/09/07/google-pixel-car-crash-detection/); [9to5Mac](https://9to5mac.com/2022/10/10/how-crash-detection-works/)
- **Walking-context safety:** distracted-walking detection from phone IMU reached 92.3% average accuracy across 21 subjects, for handheld or pocketed phones. — [PMC12390545](https://pmc.ncbi.nlm.nih.gov/articles/PMC12390545/)
- **Vulnerable road user detection from phone sensors (recurrence quantification analysis):** [arXiv 2006.06941](https://arxiv.org/pdf/2006.06941)
- The Northwestern fall system already logged Google Activity Recognition context pre-fall: 4% of real falls were labelled "bicycle". — [PMC8353784](https://pmc.ncbi.nlm.nih.gov/articles/PMC8353784/)

### Inferences
- Recommended architecture is a hierarchical, context-gated detector bank:
  1. Use the Android Activity Recognition Transition API (or an on-device TMD model) plus GPS speed to determine the mode.
  2. Run a mode-specific detector:
     - **Walking:** fall model (acc/gyro trigger + classifier + baro drop + inactivity).
     - **Cycling:** speed + rotation + acceleration multi-criteria plus post-event stillness / speed-to-zero.
     - **Driving:** speed-gated high-g + sudden speed loss + optional audio and barometer pressure spike.
  3. Share the confirmation, countdown and alert stage across modes.
- Mode transitions are the danger zone. A crash often *causes* a mode change (vehicle -> still), so the detector should keep the pre-event mode for ~30–60 s and not re-gate immediately.
- Avoid hard speed gates like 15 mph for cycling and pedestrian contexts, because slow crashes are missed.

### Gaps
- No peer-reviewed unified framework covering both vehicle crash and pedestrian fall on smartphones was found.
- No public smartphone dataset of real cycling crashes or pedestrian–vehicle impacts was found.
- Google's internal Pixel crash-detection architecture and its performance metrics are not public.

## 6. On-device Android implementation (TFLite model sizes, battery)

### Takeaway
Published smartphone fall systems favour low-complexity, cascaded processing for battery reasons, and some deploy TFLite models. However, I found no measured TFLite model sizes or mAh / %-per-hour figures specific to fall detection. The one real-world measurement shows ~12 h battery life with continuous sensing (Galaxy S5), and Android sampling-rate jitter is a documented problem.

### Cited Findings
- The Northwestern system ran on a Galaxy S5 with continuous acc/gyro sensing, a 2 g trigger plus logistic regression, GPS every 60 s and uploads. Average battery life was 12.2 h (SD 5.0), limiting monitoring to ~12 h/day. The app could not sample acc/gyro at a consistent rate due to other apps' resource demands. — [PMC8353784](https://pmc.ncbi.nlm.nih.gov/articles/PMC8353784/)
- UniMiB authors: Android does not guarantee the requested sampling rate, and it fluctuates. — [arXiv 1611.07688](https://arxiv.org/html/1611.07688v5)
- A 2024 Android system converts the IMU and CSI models to TensorFlow Lite. It runs a cheap IMU stage first and only invokes the heavier stage on suspicion, to save energy. — [arXiv 2412.09980](https://arxiv.org/pdf/2412.09980)
- A personalised fall-detection system on Android uses a low-complexity threshold first and runs the classifier only after it passes, because the phone is battery-powered. — [arXiv 2012.11195](https://arxiv.org/pdf/2012.11195)
- BeSafeBox (open-source Android) shows two battery measures: a proximity-gated model sleep and low-battery shutdown. — [GitHub](https://github.com/Foxpace/BeSafeBox_Android_app)
- TFLite quantised kernels give smaller and faster fixed-point models. On-device training also drains battery. — [TensorFlow blog](https://blog.tensorflow.org/2021/11/on-device-training-in-tensorflow-lite.html); [TF models doc](https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/running_on_mobile_tensorflowlite.md)
- 10–20 Hz sampling reportedly suffices for CNN-LSTM fall detection on SisFall, which cuts data and energy (verify source). — [PMC12788221](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12788221/)
- P2Est ran the rotation vector at 50 Hz (SENSOR_DELAY_GAME) and other sensors at SENSOR_DELAY_NORMAL to save power. — [Luo et al. 2022](https://doi.org/10.1145/3478027)
- TinyML fall detection for personal mobility vehicles is a related microcontroller deployment. — [ScienceDirect](https://www.sciencedirect.com/org/science/article/pii/S1546221821002022)

### Inferences
- Use hardware-batched sensor delivery (`maxReportLatencyUs`) and the significant-motion sensor to wake the pipeline. Run the accelerometer continuously at ~25–50 Hz.
- Sample the gyroscope at the same rate, or only in a ring buffer used after a trigger. Sample the barometer at 1–5 Hz.
- Wake the microphone and GPS only after a trigger.
- A small 1D-CNN/GRU on 2–4 s windows of 6–9 channels will typically be tens to a few hundred KB after int8 quantisation. This is my estimate, not a sourced figure; measure it.
- Run inference only after the trigger, a few times per day, to keep CPU cost negligible relative to sensor wakeups.
- A foreground service is needed for reliable background sensing. Timestamp-based resampling is required because of rate jitter.

### Gaps
- No peer-reviewed measured TFLite model size, latency or battery drain (mAh/h) for smartphone fall or crash detection was found.
- No battery comparisons were found for barometer or microphone duty-cycling on modern Android devices.
