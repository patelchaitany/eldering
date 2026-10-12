"""Throwaway reference prototype that validates docs/research/detection-params.md (features, rules,
synthetic recipes). NOT product code - the Kotlin :detection module is the implementation.
Units: g internally unless noted. Python 3 + numpy. Env NODEBOUNCE=1 disables the VEHICLE 2-sample
trigger debounce (used to exercise the suppression rules on single-sample spikes).
"""
import math
import sys
import numpy as np

G = 9.80665
FS0 = 1000  # truth grid
import os
NODEBOUNCE = bool(os.environ.get('NODEBOUNCE'))


# ---------------------------------------------------------------- geometry
def rot(axis, ang):
    axis = np.asarray(axis, float)
    axis = axis / np.linalg.norm(axis)
    x, y, z = axis
    c, s = math.cos(ang), math.sin(ang)
    C = 1 - c
    return np.array([[c + x * x * C, x * y * C - z * s, x * z * C + y * s],
                     [y * x * C + z * s, c + y * y * C, y * z * C - x * s],
                     [z * x * C - y * s, z * y * C + x * s, c + z * z * C]])


def R0_from_gravity(gp):
    """Rotation phone->world such that world up (0,0,1) maps from phone vector gp."""
    gp = np.asarray(gp, float); gp /= np.linalg.norm(gp)
    up = np.array([0, 0, 1.0])
    v = np.cross(gp, up); s = np.linalg.norm(v); c = gp @ up
    if s < 1e-9:
        return np.eye(3) if c > 0 else rot([1, 0, 0], math.pi)
    return rot(v / s, math.atan2(s, c))


POCKET = (0.0, 0.98, 0.17)      # upright thigh pocket, ~10 deg tilt
HAND = (0.0, 0.77, 0.64)        # screen to face, ~40 deg from vertical
MOUNT = (0.0, 0.94, 0.34)       # dash mount, ~70 deg from horizontal
FLAT = (0.0, 0.0, 1.0)


class Truth:
    def __init__(self, T, g_phone=POCKET, seed=1):
        self.n = int(T * FS0)
        self.t = np.arange(self.n) / FS0
        self.dyn_w = np.zeros((self.n, 3))   # world-frame dynamic accel (g)
        self.extra_p = np.zeros((self.n, 3))  # phone-frame additive (g)
        self.theta = np.zeros(self.n)         # rotation angle about phone axis u
        self.u = np.array([1.0, 0, 0])
        self.osc = np.zeros(self.n)           # small oscillation about phone x (gait swing)
        self.R0 = R0_from_gravity(g_phone)
        self.speeds = []
        self.pressure = None
        self.rng = np.random.default_rng(seed)

    def m(self, a, b):
        return (self.t >= a) & (self.t < b)

    def halfsine(self, t0, dur, amp, axis_w=None, axis_p=None):
        mk = self.m(t0, t0 + dur)
        v = amp * np.sin(math.pi * (self.t[mk] - t0) / dur)
        if axis_w is not None:
            self.dyn_w[mk] += np.outer(v, np.asarray(axis_w) / np.linalg.norm(axis_w))
        else:
            self.extra_p[mk] += np.outer(v, np.asarray(axis_p) / np.linalg.norm(axis_p))

    def freefall(self, a, b):
        mk = self.m(a, b)
        self.dyn_w[mk] = np.array([0, 0, -1.0])  # total specific force 0

    def rotate(self, t0, dur, ang, axis=(1, 0, 0)):
        """raised-cosine rotation of `ang` rad about phone axis (cumulative, single axis)."""
        self.u = np.asarray(axis, float) / np.linalg.norm(axis)
        mk = self.m(t0, t0 + dur)
        s = (self.t[mk] - t0) / dur
        self.theta[mk] += ang * (s - np.sin(2 * math.pi * s) / (2 * math.pi))
        self.theta[self.t >= t0 + dur] += ang

    def gait(self, a, b, cadence, peak, trough, swing_rad, strike_ms=30, alt=0.6):
        """cadence = steps/s. Pocket leg strikes every other step with `peak` g, other leg alt*peak."""
        mk = self.m(a, b)
        tt = self.t[mk] - a
        # smooth vertical oscillation
        base = (1 - trough) * 0.5
        self.dyn_w[mk, 2] += -base * np.cos(2 * math.pi * cadence * tt) * 0.6
        k = 0
        ts = a
        while ts < b - 0.05:
            amp = (peak - 1.0) if k % 2 == 0 else (peak - 1.0) * alt
            self.halfsine(ts, strike_ms / 1000, amp, axis_w=(0.15, 0.1, 1))
            # pre-strike dip (flight / unloading)
            self.halfsine(ts - 0.12, 0.10, -(1 - trough) * (1 if k % 2 == 0 else 0.6), axis_w=(0, 0, 1))
            ts += 1 / cadence
            k += 1
        self.osc[mk] += swing_rad * np.sin(2 * math.pi * (cadence / 2) * tt)

    def noise_motion(self, a, b, sigma):
        mk = self.m(a, b)
        self.extra_p[mk] += self.rng.normal(0, sigma, (mk.sum(), 3))

    def sine_p(self, a, b, f, amp, axis=(0, 0, 1)):
        mk = self.m(a, b)
        self.extra_p[mk] += np.outer(amp * np.sin(2 * math.pi * f * self.t[mk]), axis)

    def spin_centripetal(self, a, b, omega, r=0.03):
        mk = self.m(a, b)
        self.extra_p[mk, 1] += omega * omega * r / G

    def speed(self, t, v):
        self.speeds.append((t, v))

    def render(self):
        f_w = self.dyn_w + np.array([0, 0, 1.0])
        f_p = np.zeros_like(f_w)
        gyro = np.zeros_like(f_w)
        dth = np.gradient(self.theta, 1 / FS0)
        dos = np.gradient(self.osc, 1 / FS0)
        for i in range(self.n):
            R = self.R0 @ rot(self.u, self.theta[i]) @ rot([1, 0, 0], self.osc[i])
            f_p[i] = R.T @ f_w[i]
        gyro = np.outer(dth, self.u) + np.outer(dos, [1, 0, 0])
        return f_p + self.extra_p, gyro


def sample(truth, fs, rng_g=8.0, jitter=0.01, drop=0.002, acc_sigma=0.01, gyro_sigma=0.01, seed=7,
           gap=None):
    rng = np.random.default_rng(seed)
    f_p, gyro = truth.render()
    n = int(truth.t[-1] * fs)
    ts = np.arange(n) / fs + rng.normal(0, jitter / fs, n)
    ts = np.clip(np.sort(ts), 0, truth.t[-1])
    keep = rng.random(n) > drop
    if gap is not None:
        keep &= ~((ts >= gap[0]) & (ts < gap[1]))
    ts = ts[keep]
    # sensor model: clip at the ADC (truth rate), then boxcar-average over one output period
    # (approximates the BMI2xx OSR/averaging filter), then add white noise.
    def boxcar(sig, lim):
        sig = np.clip(sig, -lim, lim)
        C = np.concatenate([[0.0], np.cumsum(sig[:-1]) / FS0])
        tt = truth.t
        hi = np.interp(ts, tt, C); lo = np.interp(np.maximum(ts - 1.0 / fs, 0), tt, C)
        w = np.maximum(ts - np.maximum(ts - 1.0 / fs, 0), 1e-9)
        return (hi - lo) / w
    acc = np.stack([boxcar(f_p[:, k], rng_g) for k in range(3)], 1)
    acc += rng.normal(0, acc_sigma, acc.shape)
    acc = np.clip(acc, -rng_g, rng_g) * G
    gy = np.stack([boxcar(gyro[:, k], 34.9) for k in range(3)], 1)
    gy += rng.normal(0, gyro_sigma, gy.shape)
    return dict(t=ts, acc=acc, gyro=gy, gyro_t=ts, range=rng_g * G, speeds=truth.speeds, pressure=truth.pressure)


# ---------------------------------------------------------------- config
MODE = {
    #            trig  fallMin med  strong jerk  fs
    'STILL':    (1.8, 1.8, 2.4, 3.0, 50),
    'WALKING':  (1.8, 1.8, 2.4, 3.0, 50),
    'UNKNOWN':  (1.8, 1.8, 2.4, 3.0, 50),
    'RUNNING':  (3.5, 3.5, 4.5, 5.5, 150),
    'CYCLING':  (3.0, 3.0, 4.0, 5.0, 100),
    'VEHICLE':  (2.5, 3.0, 3.5, 4.5, 100),
}


# ---------------------------------------------------------------- features
def qint(gt, gw, a, b):
    """max net rotation angle (deg) from start of [a,b]."""
    mk = (gt >= a) & (gt <= b)
    t = gt[mk]; w = gw[mk]
    if len(t) < 2:
        return float('nan')
    q = np.array([1.0, 0, 0, 0]); best = 0.0
    for i in range(1, len(t)):
        dt = t[i] - t[i - 1]
        om = 0.5 * (w[i] + w[i - 1])
        ang = np.linalg.norm(om) * dt
        if ang > 0:
            ax = om / np.linalg.norm(om)
            dq = np.array([math.cos(ang / 2), *(ax * math.sin(ang / 2))])
            w0, x0, y0, z0 = q; w1, x1, y1, z1 = dq
            q = np.array([w0 * w1 - x0 * x1 - y0 * y1 - z0 * z1,
                          w0 * x1 + x0 * w1 + y0 * z1 - z0 * y1,
                          w0 * y1 - x0 * z1 + y0 * w1 + z0 * x1,
                          w0 * z1 + x0 * y1 - y0 * x1 + z0 * w1])
            q /= np.linalg.norm(q)
        best = max(best, math.degrees(2 * math.acos(min(1.0, abs(q[0])))))
    return best


def runs(mask, t):
    """list of (start_t, end_t) of True runs"""
    out = []; s = None
    for i, m in enumerate(mask):
        if m and s is None:
            s = i
        if (not m) and s is not None:
            out.append((t[s], t[i - 1])); s = None
    if s is not None:
        out.append((t[s], t[len(mask) - 1]))
    return out


def autocorr_gait(t, S, a, b, lo, hi):
    mk = (t >= a) & (t < b)
    x = S[mk]
    if len(x) < 50:
        return 0.0
    x = x - x.mean()
    if x.std() < 0.08:
        return 0.0
    best = 0.0
    for lag in range(int(lo * 100), int(hi * 100) + 1):
        if lag >= len(x) - 10:
            break
        r = np.corrcoef(x[:-lag], x[lag:])[0, 1]
        best = max(best, r)
    return best


def features(d, t0, W):
    t = d['t']; acc = d['acc']; S = np.linalg.norm(acc, axis=1) / G
    rngms = d['range']
    dt_nom = np.median(np.diff(t))
    sat = (np.abs(acc) >= 0.97 * rngms).any(1)
    grid = np.arange(t0 - 2.0, t0 + W, 0.01)
    Sg = np.interp(grid, t, S)
    ag = np.stack([np.interp(grid, t, acc[:, k]) for k in range(3)], 1)
    F = {'rateHz': 1.0 / dt_nom}
    w = (t >= t0 - 0.2) & (t <= t0 + 0.3)
    F['smvPeakG'] = S[w].max()
    F['satMs'] = 1000 * sat[(t >= t0 - 0.2) & (t <= t0 + 0.5)].sum() * dt_nom
    # above 2 g contiguous around t0
    i0 = np.argmin(np.abs(t - t0)); i = i0; j = i0
    while i > 0 and S[i - 1] >= 2.0: i -= 1
    while j < len(S) - 1 and S[j + 1] >= 2.0: j += 1
    F['aboveTwoGMs'] = 1000 * (j - i + 1) * dt_nom if S[i0] >= 2.0 else 0.0
    # impacts within window
    def impacts(a, b, thr):
        mk = np.where((t >= a) & (t <= b))[0]
        pk = []
        for k in mk[1:-1]:
            if S[k] >= thr and S[k] >= S[k - 1] and S[k] >= S[k + 1]:
                if pk and t[k] - t[pk[-1]] < 0.1:
                    if S[k] > S[pk[-1]]: pk[-1] = k
                    continue
                if pk and S[pk[-1] + 1:k].min(initial=99) > min(S[k], S[pk[-1]]) - 0.5:
                    if S[k] > S[pk[-1]]: pk[-1] = k
                    continue
                pk.append(k)
        return len(pk)
    F['impacts1s'] = impacts(t0 - 0.2, t0 + 1.0, 1.8)
    F['impacts15s'] = impacts(t0 - 0.05, t0 + 1.5, 1.8)
    # free fall before
    pre = (t >= t0 - 1.0) & (t < t0)
    tp = t[pre]; Sp = S[pre]
    F['minSmvPreG'] = Sp.min() if len(Sp) else 1.0
    ff = [r for r in runs(Sp <= 0.6, tp)]
    F['freeFallMs'] = max([1000 * (b - a + dt_nom) for a, b in ff], default=0.0)
    tff = [r for r in runs(Sp <= 0.35, tp) if t0 - r[1] <= 0.15]
    F['trueFreeFallMs'] = max([1000 * (b - a + dt_nom) for a, b in tff], default=0.0)
    post = (t > t0 + 0.05) & (t <= t0 + 1.0)
    ab = runs(S[post] <= 0.35, t[post])
    F['airborneAfterMs'] = max([1000 * (b - a + dt_nom) for a, b in ab], default=0.0)
    F['bounceAirborneMs'] = max([1000 * (b - a + dt_nom) for a, b in ab if b < t0 + 0.6], default=0.0)
    # jerk
    jk = np.gradient(Sg, 0.01)
    jm = (grid >= t0 - 0.1) & (grid <= t0 + 0.1)
    F['jerkPeakGps'] = np.abs(jk[jm]).max()
    # gravity orientation change
    gpre = acc[(t >= t0 - 2.0) & (t < t0 - 0.5)].mean(0)
    gpost = acc[(t >= t0 + 1.5) & (t < t0 + 3.5)].mean(0)
    c = gpre @ gpost / (np.linalg.norm(gpre) * np.linalg.norm(gpost))
    F['orientChangeDeg'] = math.degrees(math.acos(max(-1, min(1, c))))
    F['_gpre'] = gpre / np.linalg.norm(gpre)
    # gyro
    gt, gw = d['gyro_t'], d['gyro']
    if gt is not None and len(gt):
        F['gyroNetRotDeg'] = qint(gt, gw, t0 - 1.0, t0 + 0.5)
        F['fastRot05sDeg'] = max(qint(gt, gw, s, s + 0.5) for s in np.arange(t0 - 1.0, t0 + 0.01, 0.1))
        F['preRotDeg'] = qint(gt, gw, t0 - 0.5, t0)
        F['strikeRotDeg'] = qint(gt, gw, t0, t0 + 1.0)
    # stillness
    st = []
    for s in np.arange(t0 + 2.0, t0 + W - 1.0 + 1e-9, 0.25):
        mk = (grid >= s) & (grid < s + 1.0)
        st.append(Sg[mk].std())
    st = np.array(st)
    F['stillFraction'] = float((st <= 0.05).mean()) if len(st) else 0.0
    F['restNoiseG'] = float(np.median(st)) if len(st) else 1.0
    # recovery: gait or pickup
    gaitpost = autocorr_gait(grid, Sg, t0 + 2.0, t0 + W, 0.35, 1.3)
    rest_g = ag[(grid >= t0 + 1.5) & (grid < t0 + 3.5)].mean(0)
    late_g = ag[(grid >= t0 + W - 2.0)].mean(0)
    cc = rest_g @ late_g / (np.linalg.norm(rest_g) * np.linalg.norm(late_g))
    pick = math.degrees(math.acos(max(-1, min(1, cc)))) >= 30 and (st.max() if len(st) else 0) >= 0.08
    F['recovered'] = float(gaitpost >= 0.5 or pick)
    F['gaitPre'] = autocorr_gait(grid, Sg, t0 - 2.0, t0 - 0.3, 0.25, 0.8)
    F['gaitPost'] = autocorr_gait(grid, Sg, t0 + 0.5, t0 + 3.5, 0.25, 0.8)
    # horizontal ratio at impact & dv
    gimp = acc[(t >= t0 - 0.3) & (t < t0 - 0.05)].mean(0); gimp /= np.linalg.norm(gimp)
    ap = acc[np.argmin(np.abs(t - t0))]
    dvec = ap - G * gimp
    h = dvec - (dvec @ gimp) * gimp
    F['horizRatio'] = np.linalg.norm(h) / max(1e-6, np.linalg.norm(dvec))
    i = i0; j = i0
    while i > 0 and S[i - 1] >= 1.5: i -= 1
    while j < len(S) - 1 and S[j + 1] >= 1.5: j += 1
    dyn = np.linalg.norm(acc[i:j + 1] - G * F['_gpre'], axis=1)
    F['dvLowerBoundMps'] = float(np.trapezoid(dyn, t[i:j + 1])) if j > i else float(dyn[0] * dt_nom)
    # speed
    sp = d['speeds']
    b4 = [v for (ts_, v) in sp if t0 - 15 <= ts_ <= t0 - 0.5]
    af = [v for (ts_, v) in sp if t0 + 3 <= ts_ <= t0 + W]
    F['speedBeforeMps'] = float(np.median(b4)) if b4 else float('nan')
    F['speedAfterMps'] = float(max(af)) if af else float('nan')
    if d.get('pressure') is not None:
        pt, pv = d['pressure']
        a1 = pv[(pt >= t0 - 3) & (pt < t0 - 0.5)]; a2 = pv[(pt >= t0 + 2) & (pt < t0 + 5)]
        if len(a1) and len(a2):
            F['pressureDeltaHpa'] = float(np.median(a2) - np.median(a1))
    return F


def evaluate(d, mode, W_fall=10.0, W_veh=15.0, verbose=False):
    t = d['t']; S = np.linalg.norm(d['acc'], axis=1) / G
    sat = (np.abs(d['acc']) >= 0.97 * d['range']).any(1)
    trig, fallMin, med, strong, jerkThr = MODE[mode]
    rate = 1 / np.median(np.diff(t))
    cap = max(0.7, math.cos(math.pi * (1 / rate) / (2 * 0.08)))
    rangeG = d['range'] / G
    eff = lambda x: min(x * cap, 0.9 * rangeG)
    k = 0; out = []
    while k < len(t):
        if not ((S[k] >= eff(trig)) or sat[k]):
            k += 1; continue
        if mode == 'VEHICLE' and not sat[k] and not NODEBOUNCE:
            nb = (t > t[k]) & (t <= t[k] + 0.03) & (S >= eff(trig))
            if not nb.any():
                k += 1; continue
        win = (t >= t[k]) & (t <= t[k] + 0.5)
        idx = np.where(win)[0]
        k0 = idx[np.argmax(S[idx])]
        t0 = t[k0]
        W = W_veh if mode == 'VEHICLE' else W_fall
        if t0 - 2.0 < t[0] or t0 + W > t[-1]:
            k = k0 + 1; continue
        F = features(d, t0, W)
        res = decide(F, mode, med, strong, fallMin, jerkThr, eff, rangeG)
        out.append((round(t0, 3), res))
        if verbose:
            print('  cand t0=%.2f -> %s' % (t0, res))
        if res[0] in ('VEHICLE_CRASH', 'PEDESTRIAN_IMPACT', 'FALL'):
            return res, F, out
        nxt = np.where(t >= t0 + 0.3)[0]
        k = nxt[0] if len(nxt) else len(t)
    return ('NONE', 0, 'no-event'), None, out


def decide(F, mode, med, strong, fallMin, jerkThr, eff, rangeG):
    pk = F['smvPeakG']; satd = F['satMs'] >= 5
    sb, sa = F['speedBeforeMps'], F['speedAfterMps']
    vehicle_ctx = mode == 'VEHICLE' or (not math.isnan(sb) and sb >= 8.0)
    moving_vehicle = vehicle_ctx and (math.isnan(sb) or sb >= 4.2)
    # ---- global suppressions
    if F['aboveTwoGMs'] <= max(30.0, 1000.0 / F['rateHz'] + 1) and F['orientChangeDeg'] < 15 and F.get('gyroNetRotDeg', 0) < 20 and F['minSmvPreG'] > 0.6:
        return ('NONE', 0, 'impulse_only')
    sharp = F['rateHz'] >= 40 and F['aboveTwoGMs'] <= 30.0
    gp = F['_gpre']; hand = math.degrees(math.acos(min(1.0, abs(gp[1])))) >= 30
    F['handHeldBefore'] = float(hand)
    drop = (F['trueFreeFallMs'] >= 200 and (sharp or F['bounceAirborneMs'] >= 40 or hand)
            and (F['restNoiseG'] <= 0.012 or F['recovered']))
    toss = (F['airborneAfterMs'] >= 150 and pk < eff(5.0) and (F['restNoiseG'] <= 0.012 or F['recovered']))
    # ---- vehicle crash
    if vehicle_ctx:
        if not math.isnan(sb) and sb < 4.2:
            pass
        elif not ((pk >= eff(4.0)) or F['satMs'] >= 10):
            pass
        elif not (F['aboveTwoGMs'] >= 40 or F['dvLowerBoundMps'] >= 2.0):
            pass
        elif not math.isnan(sa) and not math.isnan(sb) and sa >= max(4.2, 0.5 * sb):
            return ('NONE', 0, 'continued_driving')
        else:
            s = 0.4
            if not math.isnan(sa) and sa <= 1.5: s += 0.3
            if math.isnan(sa): s -= 0.15
            if not math.isnan(sb) and sb >= 7.0: s += 0.1
            if F['satMs'] >= 20 or pk >= eff(8.0): s += 0.1
            if F['dvLowerBoundMps'] >= 4.0: s += 0.1
            if F.get('gyroNetRotDeg', 0) >= 45: s += 0.05
            if F['impacts1s'] >= 2: s += 0.05
            if F.get('pressureDeltaHpa') is not None and abs(F['pressureDeltaHpa']) >= 0.5: s += 0.1
            if math.isnan(sa): s = min(s, 0.7)
            s = min(1.0, s)
            if s >= 0.6:
                return ('VEHICLE_CRASH', round(s, 2), 'crash')
    # ---- pedestrian strike
    if not moving_vehicle and not drop and not toss:
        if (pk >= eff(5.0) or F['satMs'] >= 10) and F.get('preRotDeg', 0) <= 30:
            s = 0.35
            if F['horizRatio'] >= 0.6: s += 0.15
            if F.get('strikeRotDeg', 0) >= 90: s += 0.15
            if F['impacts15s'] >= 2: s += 0.1
            if F['airborneAfterMs'] >= 150: s += 0.1
            if F['satMs'] >= 10 or pk >= eff(8.0): s += 0.1
            if F['orientChangeDeg'] >= 45: s += 0.15
            if F['stillFraction'] >= 0.7: s += 0.15
            if F['dvLowerBoundMps'] >= 3.0: s += 0.1
            if F['recovered']: s -= 0.25
            F['strikeScore'] = s
            if s >= 0.7:
                return ('PEDESTRIAN_IMPACT', round(min(1, s), 2), 'strike')
    # ---- fall
    if drop:
        return ('NONE', 0, 'phone_drop')
    if toss:
        return ('NONE', 0, 'toss')
    if F['gaitPre'] >= 0.5 and F['gaitPost'] >= 0.5:
        return ('NONE', 0, 'gait_continues')
    if moving_vehicle:
        fallMinV = eff(3.0)
    else:
        fallMinV = eff(fallMin)
    if not (pk >= fallMinV or satd):
        return ('NONE', 0, 'below_fall_min')
    violence = 0
    if pk >= eff(med) or satd: violence += 1
    if F['freeFallMs'] >= 60: violence += 1
    if F.get('fastRot05sDeg', 0) >= 75 or (F.get('fastRot05sDeg') is None and F['orientChangeDeg'] >= 60): violence += 1
    if F['jerkPeakGps'] >= jerkThr: violence += 1
    if violence < 2:
        return ('NONE', 0, 'violence<2 (%d)' % violence)
    if moving_vehicle:
        posture_ok = F['orientChangeDeg'] >= 35
    else:
        posture_ok = F['orientChangeDeg'] >= 35 or F['stillFraction'] >= 0.7
    if not posture_ok:
        return ('NONE', 0, 'no_posture_change')
    if F['recovered']:
        return ('NONE', 0, 'recovered')
    s = 0.2
    s += 0.25 if (pk >= eff(strong) or satd) else (0.15 if pk >= eff(med) else 0.05)
    if F['freeFallMs'] >= 60: s += 0.15
    if F.get('gyroNetRotDeg', 0) >= 60: s += 0.15
    s += 0.2 if F['orientChangeDeg'] >= 60 else (0.1 if F['orientChangeDeg'] >= 35 else 0)
    if F['stillFraction'] >= 0.7: s += 0.25
    if F.get('pressureDeltaHpa') is not None and F['pressureDeltaHpa'] >= 0.04: s += 0.1
    s = min(1.0, s)
    if s >= 0.65:
        return ('FALL', round(s, 2), 'fall')
    return ('NONE', round(s, 2), 'fall_score_low')
