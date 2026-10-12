import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scen import *
def ladder():
    tr = Truth(22, POCKET); tr.noise_motion(0, 5, 0.02)
    tr.freefall(5.0, 5.5); orient_to(tr, 5.0, 0.6, (0, 0.05, 1.0))
    tr.halfsine(5.5, 0.07, 6.0, axis_w=UP); tr.halfsine(5.65, 0.05, 1.5, axis_w=UP)
    still_body(tr, 5.8, 22); return tr
def carpet_drop():
    tr = Truth(18, HAND); tr.noise_motion(0, 5, 0.02)
    tr.freefall(5.0, 5.45); orient_to(tr, 5.0, 0.45, (0, 0, -1)); tr.spin_centripetal(5.0, 5.45, 6.0)
    tr.halfsine(5.45, 0.035, 4.0, axis_w=UP)
    tr.noise_motion(5.6, 18, 0.0); return tr
for name, b, mode, fs in [('ladder_fall', ladder, 'WALKING', 50), ('carpet_drop_left', carpet_drop, 'STILL', 25), ('carpet_drop_left_50', carpet_drop, 'WALKING', 50)]:
    d = sample(b(), fs, rng_g=8, acc_sigma=0.005)
    r, F, c = evaluate(d, mode)
    print('%-22s %s cands=%s' % (name, r, [x[1][2] for x in c][:5]))
    if F: print('   ', {k: round(float(F[k]),2) for k in ('smvPeakG','aboveTwoGMs','trueFreeFallMs','bounceAirborneMs','restNoiseG','orientChangeDeg','stillFraction') })
