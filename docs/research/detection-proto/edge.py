import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scen import *

def knees():
    tr = Truth(22, POCKET); tr.gait(0, 5.0, 1.8, 1.7, 0.6, 0.35); tr.noise_motion(0, 5, 0.02)
    tr.halfsine(5.0, 0.3, -0.25, axis_w=UP); tr.rotate(5.0, 0.4, math.radians(30), (1, 0, 0))
    tr.halfsine(5.3, 0.06, 0.95, axis_w=UP); still_body(tr, 5.5, 22); return tr

def plop():
    tr = Truth(18, POCKET); tr.noise_motion(0, 5, 0.02)
    tr.halfsine(5.0, 0.45, -0.45, axis_w=UP); orient_to(tr, 5.0, 0.5, (0, 0.05, 1.0))
    tr.halfsine(5.45, 0.07, 1.8, axis_w=UP); tr.noise_motion(5.6, 18, 0.01); return tr

def jump():
    tr = Truth(18, POCKET); tr.gait(0, 5.0, 1.8, 1.7, 0.6, 0.35)
    tr.halfsine(5.0, 0.15, 1.0, axis_w=UP); tr.freefall(5.15, 5.45); tr.halfsine(5.45, 0.06, 3.2, axis_w=UP)
    tr.gait(5.6, 18, 1.8, 1.7, 0.6, 0.35); tr.noise_motion(0, 18, 0.02); return tr

def lie_on_bed():
    tr = Truth(18, POCKET); tr.noise_motion(0, 5, 0.02)
    tr.halfsine(5.0, 1.5, -0.15, axis_w=UP); orient_to(tr, 5.0, 2.0, (0, 0.05, 1.0))
    tr.halfsine(6.8, 0.2, 0.4, axis_w=UP); still_body(tr, 7.0, 18); return tr

def nogyro(d):
    d = dict(d); d['gyro_t'] = None; return d

for name, b, mode, fs, rg, ng in [('knees', knees, 'WALKING', 50, 8, False), ('plop', plop, 'WALKING', 50, 8, False),
                              ('jump', jump, 'WALKING', 50, 8, False), ('lie_on_bed', lie_on_bed, 'WALKING', 50, 8, False),
                              ('fall_still_25_nogyro', fall_from_still, 'STILL', 25, 8, True),
                              ('ped_low_still_25_nogyro', pedestrian_low, 'STILL', 25, 8, True),
                              ('fall_walk_nogyro', fall, 'WALKING', 50, 8, True),
                              ('ped_40_nogyro', pedestrian, 'WALKING', 50, 8, True),
                              ('fall_walk_gap', fall, 'WALKING', 50, 8, False)]:
    d = sample(b(), fs, rng_g=rg, acc_sigma=0.005, gap=(5.05, 5.17) if name.endswith('gap') else None)
    if ng: d = nogyro(d)
    r, F, c = evaluate(d, mode)
    print('%-26s %s cands=%s' % (name, r, [x[1][2] for x in c][:5]))
