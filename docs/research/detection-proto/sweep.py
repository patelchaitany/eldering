import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scen import *
print('crash speed sweep (T=150ms, 8g, mount, 100Hz):')
for v in (10, 14, 16, 20, 25, 30):
    r, F, _ = evaluate(sample(crash(v, 0.15), 100, rng_g=8, acc_sigma=0.005), 'VEHICLE')
    print('  %2d km/h peak=%.1fg -> %s' % (v, math.pi*(v/3.6)/(2*0.15)/G, r[:2]))
def fall_amp(a, trough=0.6, rotdur=0.45):
    tr = Truth(22, POCKET); tr.gait(0, 5.0, 1.8, 1.7, 0.6, 0.35); tr.noise_motion(0, 5.0, 0.02)
    tr.halfsine(5.0, 0.35, -trough, axis_w=UP); orient_to(tr, 4.95, rotdur, (0, 0.05, 1.0))
    tr.halfsine(5.35, 0.05, a, axis_w=(0.3, 0, 1)); still_body(tr, 5.6, 22); return tr
print('fall impact sweep (50Hz, trough 0.4g, rot 90deg/0.45s):')
for a in (0.6, 0.9, 1.2, 1.5, 2.0, 3.0):
    r, F, c = evaluate(sample(fall_amp(a), 50, rng_g=8, acc_sigma=0.005), 'WALKING')
    print('  dyn %.1f (peak~%.1fg) -> %s %s' % (a, (F or {}).get('smvPeakG', float('nan')), r[:2], [x[1][2] for x in c][:3]))
print('fall without trough, slow rotation (1.0s):')
for a in (1.2, 2.0, 3.0):
    r, F, c = evaluate(sample(fall_amp(a, trough=0.1, rotdur=1.0), 50, rng_g=8, acc_sigma=0.005), 'WALKING')
    print('  dyn %.1f -> %s %s' % (a, r[:2], [x[1][2] for x in c][:3]))
def ped_amp(a):
    tr = Truth(24, POCKET); tr.gait(0, 6.0, 1.8, 1.7, 0.6, 0.35); tr.noise_motion(0, 6.0, 0.02)
    tr.halfsine(6.0, 0.08, a, axis_w=(1, 0, 0.2)); tr.rotate(6.03, 0.6, math.radians(120), (0.3, 0, 1))
    tr.halfsine(6.9, 0.05, 2.5, axis_w=UP); still_body(tr, 7.1, 24); return tr
print('strike amplitude sweep (50Hz, 8g):')
for a in (3, 4, 5, 6, 8, 12):
    r, F, c = evaluate(sample(ped_amp(a), 50, rng_g=8, acc_sigma=0.005), 'WALKING')
    print('  %2dg -> %s %s strikeScore=%s' % (a, r[:2], [x[1][2] for x in c][:3], (F or {}).get('strikeScore')))
