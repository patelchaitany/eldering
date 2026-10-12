"""Synthetic scenario recipes from docs/research/detection-params.md §5 + expected outcomes.
Run: cd docs/research/detection-proto && python3 scen.py   (python3 + numpy)
Throwaway reference prototype used to validate the parameters; NOT product code.
"""
import math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from proto import *

UP = (0, 0, 1)


def orient_to(tr, t0, dur, target, axis=(1, 0, 0)):
    """rotate about phone axis so that phone-frame gravity ends near `target` (choose sign)."""
    target = np.asarray(target, float); target /= np.linalg.norm(target)
    best = None
    for ang in np.radians(np.arange(-360, 361, 1)):
        R = tr.R0 @ rot(axis, ang)
        g = R.T @ np.array([0, 0, 1.0])
        err = math.acos(max(-1, min(1, g @ target)))
        if best is None or err < best[0] - 1e-9 or (abs(err - best[0]) < 1e-9 and abs(ang) < abs(best[1])):
            best = (err, ang)
    tr.rotate(t0, dur, best[1], axis)
    return math.degrees(best[1])


def speeds(tr, a, b, v, every=1.0, sigma=0.0, rng=None):
    t = a
    while t < b:
        vv = v(t) if callable(v) else v
        if sigma and rng is not None:
            vv = max(0.0, vv + rng.normal(0, sigma))
        tr.speed(t, vv); t += every


def still_body(tr, a, b):
    tr.noise_motion(a, b, 0.003)
    tr.sine_p(a, b, 0.25, 0.005, axis=(0, 0.6, 0.8))


def walking(fs=50):
    tr = Truth(16, POCKET); tr.gait(0, 16, 1.8, 1.7, 0.6, 0.35); tr.noise_motion(0, 16, 0.02)
    speeds(tr, 0, 16, 1.4); return tr


def running():
    tr = Truth(16, POCKET); tr.gait(0, 16, 2.8, 4.2, 0.35, 0.6, strike_ms=40); tr.noise_motion(0, 16, 0.04)
    speeds(tr, 0, 16, 3.0); return tr


def stairs():
    tr = Truth(16, POCKET); tr.gait(0, 16, 1.6, 2.4, 0.55, 0.5, strike_ms=40); tr.noise_motion(0, 16, 0.03)
    speeds(tr, 0, 16, 0.6); return tr


def phone_drop(pickup=False):
    tr = Truth(18, HAND); tr.noise_motion(0, 5, 0.02)
    tr.freefall(5.0, 5.45); orient_to(tr, 5.0, 0.45, (0, 0, -1)); tr.spin_centripetal(5.0, 5.45, 6.0)
    tr.halfsine(5.45, 0.015, 12, axis_w=UP)
    tr.freefall(5.465, 5.55); tr.halfsine(5.55, 0.012, 4, axis_w=UP)
    tr.freefall(5.562, 5.60); tr.halfsine(5.60, 0.010, 1.5, axis_w=UP)
    if pickup:
        tr.halfsine(8.0, 0.8, 0.6, axis_w=UP)
        tr.rotate(8.0, 0.8, math.radians(140), (1, 0, 0))
        tr.noise_motion(8.8, 18, 0.03)
    return tr


def thrown_on_bed():
    tr = Truth(18, HAND); tr.noise_motion(0, 5, 0.02)
    tr.halfsine(5.0, 0.25, 2.0, axis_w=(0.7, 0, 0.7))
    tr.freefall(5.25, 5.60); orient_to(tr, 5.25, 0.35, (0.0, 0.14, 0.99)); tr.spin_centripetal(5.25, 5.6, 5.0)
    mk = tr.m(5.6, 5.95); tt = tr.t[mk] - 5.6
    tr.dyn_w[mk, 2] += 1.2 * np.exp(-tt / 0.08) * np.sin(2 * math.pi * 8 * tt)
    return tr


def sit_down():
    tr = Truth(18, POCKET); tr.noise_motion(0, 5, 0.02)
    tr.halfsine(5.0, 0.8, -0.28, axis_w=UP)
    orient_to(tr, 5.0, 1.0, (0, 0.05, 1.0))
    tr.halfsine(5.85, 0.12, 1.05, axis_w=UP)
    tr.noise_motion(6.0, 18, 0.015)
    for s in (8.0, 11.5, 15.0):
        tr.sine_p(s, s + 0.5, 2.0, 0.08, axis=(0.6, 0.8, 0))
    return tr


def pothole(loose=False):
    tr = Truth(24, MOUNT if not loose else (0.0, 0.2, 0.98))
    tr.noise_motion(0, 24, 0.04); tr.sine_p(0, 24, 25, 0.03)
    speeds(tr, 0, 24, 11.1)
    tr.halfsine(8.0, 0.04, -0.6, axis_w=UP); tr.halfsine(8.04, 0.06, 1.2, axis_w=UP)
    mk = tr.m(8.1, 8.4); tt = tr.t[mk] - 8.1
    tr.dyn_w[mk, 2] += 0.3 * np.exp(-tt / 0.1) * np.sin(2 * math.pi * 12 * tt)
    if loose:
        tr.halfsine(8.05, 0.012, 5.0, axis_w=UP)
    return tr


def hard_brake(slide=False):
    tr = Truth(30, MOUNT if not slide else FLAT)
    tr.noise_motion(0, 30, 0.04); tr.sine_p(0, 30, 25, 0.03)
    v0, dec = 16.7, 0.9 * G
    tstop = 8.0 + v0 / dec
    speeds(tr, 0, 30, lambda t: v0 if t < 8 else max(0.0, v0 - dec * (t - 8)))
    mk = tr.m(8.0, tstop); tt = tr.t[mk] - 8.0
    tr.dyn_w[mk, 0] += -0.9 * np.minimum(1, tt / 0.3)
    if slide:
        tr.halfsine(8.4, 0.4, 0.4, axis_p=(0, 1, 0))
        tr.freefall(8.8, 9.1); tr.rotate(8.8, 0.3, math.radians(20), (1, 0, 0))
        tr.halfsine(9.1, 0.015, 12.0, axis_w=UP); tr.halfsine(9.13, 0.012, 2.0, axis_w=UP)
    return tr


def door_slam():
    tr = Truth(24, (0.3, 0.9, 0.3)); tr.noise_motion(0, 24, 0.005)
    speeds(tr, 0, 24, 0.0)
    tr.halfsine(8.0, 0.010, 5.0, axis_w=(1, 0, 0))
    mk = tr.m(8.01, 8.09); tt = tr.t[mk] - 8.01
    tr.dyn_w[mk, 0] += 0.5 * np.exp(-tt / 0.03) * np.sin(2 * math.pi * 40 * tt)
    return tr


def mount_vibration():
    tr = Truth(40, MOUNT); tr.noise_motion(0, 40, 0.06); tr.sine_p(0, 40, 28, 0.05)
    speeds(tr, 0, 40, 25.0)
    for s in np.arange(3, 38, 7.0):
        tr.halfsine(s, 0.03, 0.5, axis_w=UP)
    return tr


def fall(getup=False):
    tr = Truth(22, POCKET); tr.gait(0, 5.0, 1.8, 1.7, 0.6, 0.35); tr.noise_motion(0, 5.0, 0.02)
    speeds(tr, 0, 5, 1.4); speeds(tr, 6, 22, 0.0)
    tr.halfsine(5.0, 0.35, -0.6, axis_w=UP)
    orient_to(tr, 4.95, 0.45, (0, 0.05, 1.0))
    tr.halfsine(5.35, 0.05, 3.5, axis_w=(0.3, 0, 1)); tr.halfsine(5.55, 0.04, 1.0, axis_w=UP)
    if not getup:
        still_body(tr, 5.7, 22)
    else:
        still_body(tr, 5.7, 9.0)
        tr.rotate(9.0, 1.5, -tr.theta[-1], tr.u); tr.halfsine(9.0, 1.5, 0.3, axis_w=UP)
        tr.gait(10.5, 22, 1.8, 1.7, 0.6, 0.35); tr.noise_motion(10.5, 22, 0.02)
    return tr


def fall_from_still():
    tr = Truth(22, POCKET); tr.noise_motion(0, 5, 0.01)
    tr.halfsine(5.0, 0.5, -0.5, axis_w=UP)
    orient_to(tr, 5.0, 0.6, (0, 0.05, 1.0))
    tr.halfsine(5.55, 0.06, 2.2, axis_w=UP)
    still_body(tr, 5.7, 22)
    return tr


def running_fall():
    tr = Truth(22, POCKET); tr.gait(0, 5.0, 2.8, 4.2, 0.35, 0.6, strike_ms=40); tr.noise_motion(0, 5, 0.04)
    speeds(tr, 0, 5, 3.0); speeds(tr, 7, 22, 0.0)
    orient_to(tr, 5.0, 0.4, (0, -0.3, 0.95))
    tr.halfsine(5.05, 0.25, -0.5, axis_w=UP)
    tr.halfsine(5.4, 0.05, 5.5, axis_w=(0.5, 0, 1))
    mk = tr.m(5.45, 6.0); tr.dyn_w[mk, 0] += -0.5
    tr.noise_motion(5.45, 6.0, 0.2)
    still_body(tr, 6.0, 22)
    return tr


def pedestrian(speed_kmh=40):
    tr = Truth(24, POCKET); tr.gait(0, 6.0, 1.8, 1.7, 0.6, 0.35); tr.noise_motion(0, 6.0, 0.02)
    speeds(tr, 0, 6, 1.4); speeds(tr, 9, 24, 0.0)
    tr.halfsine(6.0, 0.06, 14.0, axis_w=(1, 0, 0.2))
    tr.rotate(6.02, 0.8, math.radians(270), (0.3, 0, 1))
    tr.halfsine(6.14, 0.03, 6.5, axis_w=(0.5, 0, -1))
    mk = tr.m(6.2, 7.0); tr.dyn_w[mk, 2] += -0.6
    tr.halfsine(7.1, 0.04, 4.5, axis_w=UP)
    mk = tr.m(7.15, 7.9); tr.dyn_w[mk, 0] += -0.7; tr.noise_motion(7.15, 7.9, 0.15)
    still_body(tr, 7.9, 24)
    return tr


def pedestrian_low():
    tr = Truth(22, POCKET); tr.noise_motion(0, 5, 0.01); speeds(tr, 0, 22, 0.0)
    tr.halfsine(5.0, 0.08, 5.5, axis_w=(1, 0, 0))
    tr.rotate(5.03, 0.5, math.radians(100), (0.2, 0, 1))
    tr.halfsine(5.6, 0.05, 2.2, axis_w=UP)
    still_body(tr, 5.8, 22)
    return tr


def crash(v_kmh=50, T=0.12, g_phone=MOUNT, tunnel=False):
    tr = Truth(30, g_phone); rng = np.random.default_rng(3)
    tr.noise_motion(0, 10, 0.04); tr.sine_p(0, 10, 25, 0.03)
    v = v_kmh / 3.6
    speeds(tr, 0, 10, v, sigma=0.3, rng=rng)
    if not tunnel:
        tr.speed(11.0, 2.0); speeds(tr, 12, 30, 0.0)
    amp = math.pi * v / (2 * T) / G
    tr.halfsine(10.0, T, amp, axis_w=(-1, 0, 0)); tr.halfsine(10.0, 0.1, 2.0, axis_w=UP)
    tr.rotate(10.02, 0.15, math.radians(25), (0, 1, 0))
    tr.halfsine(10.4, 0.05, 3.0, axis_w=(-1, 0, 0.3))
    tr.noise_motion(10.6, 30, 0.01); tr.sine_p(10.6, 30, 25, 0.02)
    return tr


def bike_crash():
    tr = Truth(24, POCKET); tr.noise_motion(0, 8, 0.08); tr.sine_p(0, 8, 1.3, 0.2, axis=(0, 0.3, 0.95))
    tr.osc[tr.m(0, 8)] += 0.6 * np.sin(2 * math.pi * 1.3 * tr.t[tr.m(0, 8)])
    speeds(tr, 0, 8, 5.5); speeds(tr, 10, 24, 0.0)
    orient_to(tr, 8.0, 0.5, (0.9, 0.1, 0.4), axis=(0, 1, 0))
    tr.halfsine(8.3, 0.06, 4.5, axis_w=(0.4, 0, 1))
    mk = tr.m(8.36, 8.9); tr.dyn_w[mk, 0] += -0.6; tr.noise_motion(8.36, 8.9, 0.2)
    still_body(tr, 8.9, 24)
    return tr


def with_pressure(tr, t0, dh):
    pt = np.arange(0, tr.t[-1], 0.1)
    pv = 1013.25 + np.random.default_rng(5).normal(0, 0.003, len(pt))
    pv[pt >= t0] += 0.12 * dh
    tr.pressure = (pt, pv)
    return tr


CASES = [
    # name, builder, mode, fs, rangeG, expected kind
    ('walk', walking, 'WALKING', 50, 8, 'NONE'),
    ('run', running, 'RUNNING', 50, 8, 'NONE'),
    ('stairs_down', stairs, 'WALKING', 50, 8, 'NONE'),
    ('phone_drop', phone_drop, 'WALKING', 50, 8, 'NONE'),
    ('phone_drop_100', phone_drop, 'WALKING', 100, 8, 'NONE'),
    ('phone_drop_pickup', lambda: phone_drop(True), 'STILL', 25, 8, 'NONE'),
    ('thrown_on_bed', thrown_on_bed, 'STILL', 25, 8, 'NONE'),
    ('sit_down', sit_down, 'WALKING', 50, 8, 'NONE'),
    ('pothole_mounted', pothole, 'VEHICLE', 100, 8, 'NONE'),
    ('pothole_loose', lambda: pothole(True), 'VEHICLE', 100, 8, 'NONE'),
    ('hard_brake', hard_brake, 'VEHICLE', 100, 8, 'NONE'),
    ('hard_brake_slide', lambda: hard_brake(True), 'VEHICLE', 100, 8, 'NONE'),
    ('door_slam', door_slam, 'VEHICLE', 100, 8, 'NONE'),
    ('mount_vib_50', mount_vibration, 'VEHICLE', 50, 8, 'NONE'),
    ('mount_vib_100', mount_vibration, 'VEHICLE', 100, 8, 'NONE'),
    ('fall_walk', fall, 'WALKING', 50, 8, 'FALL'),
    ('fall_walk_200', fall, 'WALKING', 200, 8, 'FALL'),
    ('fall_getup', lambda: fall(True), 'WALKING', 50, 8, 'NONE'),
    ('fall_still_25', fall_from_still, 'STILL', 25, 8, 'FALL'),
    ('fall_run_4g', running_fall, 'RUNNING', 50, 4, 'FALL'),
    ('bike_crash', bike_crash, 'CYCLING', 50, 8, 'FALL'),
    ('ped_40', pedestrian, 'WALKING', 50, 8, 'PEDESTRIAN_IMPACT'),
    ('ped_40_100_16g', pedestrian, 'WALKING', 100, 16, 'PEDESTRIAN_IMPACT'),
    ('ped_low_still_25', pedestrian_low, 'STILL', 25, 8, 'PEDESTRIAN_IMPACT|FALL'),
    ('crash_50_16g', crash, 'VEHICLE', 100, 16, 'VEHICLE_CRASH'),
    ('crash_50_4g_pocket', lambda: crash(g_phone=POCKET), 'VEHICLE', 100, 4, 'VEHICLE_CRASH'),
    ('crash_20_vv', lambda: crash(20, 0.15), 'VEHICLE', 100, 8, 'VEHICLE_CRASH'),
    ('crash_50_tunnel', lambda: crash(tunnel=True), 'VEHICLE', 100, 8, 'VEHICLE_CRASH'),
    ('crash_50_50hz', crash, 'VEHICLE', 50, 8, 'VEHICLE_CRASH'),
    ('crash_50_400hz', crash, 'VEHICLE', 400, 16, 'VEHICLE_CRASH'),
    ('fall_still_25_baro', lambda: with_pressure(fall_from_still(), 5.5, -0.65), 'STILL', 25, 8, 'FALL'),
    ('stairs_baro', lambda: with_pressure(stairs(), 5.0, -2.5), 'WALKING', 50, 8, 'NONE'),
]

if __name__ == '__main__':
    only = sys.argv[1:]
    bad = 0
    for name, b, mode, fs, rg, exp in CASES:
        if only and name not in only:
            continue
        d = sample(b(), fs, rng_g=rg, acc_sigma=0.005)
        res, F, cands = evaluate(d, mode, verbose=bool(only))
        ok = res[0] in exp.split('|')
        bad += (not ok)
        keys = ['smvPeakG', 'satMs', 'aboveTwoGMs', 'freeFallMs', 'trueFreeFallMs', 'airborneAfterMs', 'jerkPeakGps',
                'gyroNetRotDeg', 'fastRot05sDeg', 'preRotDeg', 'strikeRotDeg', 'orientChangeDeg', 'stillFraction',
                'restNoiseG', 'recovered', 'horizRatio', 'dvLowerBoundMps', 'speedBeforeMps', 'speedAfterMps',
                'impacts1s', 'impacts15s', 'gaitPre', 'gaitPost', 'pressureDeltaHpa', 'strikeScore']
        feat = ''
        if F is not None:
            feat = ' '.join('%s=%.2f' % (k, F[k]) for k in keys if k in F and isinstance(F[k], (int, float, np.floating)))
        print('%-20s %-8s %3dHz %2dg exp=%-24s got=%-18s conf=%-5s %-28s ncand=%d %s' % (
            name, mode, fs, rg, exp, res[0], res[1], res[2], len(cands), 'OK' if ok else '<<<< MISMATCH'))
        if only and F is not None:
            print('   ', feat)
        if only:
            for c in cands[:12]:
                print('    cand', c)
    print('mismatches:', bad)
