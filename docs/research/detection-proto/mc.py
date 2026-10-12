import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sys
from scen import *
from collections import Counter
res = {}
for name, b, mode, fs, rg, exp in CASES:
    tr = b()
    c = Counter(); confs = []
    for seed in range(8):
        for f in (fs, ) if seed < 6 else ((100 if fs < 100 else 50),):
            d = sample(tr, f, rng_g=rg, acc_sigma=0.005, seed=100 + seed, jitter=0.02)
            r, F, _ = evaluate(d, mode)
            c[r[0]] += 1
            if r[0] != 'NONE': confs.append(r[1])
    ok = all(k in exp.split('|') for k in c)
    print('%-20s exp=%-24s %s conf[min=%s] %s' % (name, exp, dict(c), min(confs) if confs else '-', 'OK' if ok else '<<<<'))
