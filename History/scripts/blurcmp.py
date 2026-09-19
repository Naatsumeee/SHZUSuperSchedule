import sys
import numpy as np
from PIL import Image

BART = 2885          # 底栏顶边（px）
H_EXP = 3200         # 屏幕高
W_EXP = 1440
PAD = 8              # 去掉洋红边框 / 边缘


def load(p):
    return np.asarray(Image.open(p).convert("RGB")).astype(np.float64)


def blk(img, y0, y1):
    return img[y0:y1, PAD:W_EXP - PAD]


def energy(b):
    g = b.mean(axis=2)
    return float(np.abs(np.diff(g, axis=1)).mean()), float(np.abs(np.diff(g, axis=0)).mean())


def corr(a, b):
    a = a.ravel() - a.mean()
    b = b.ravel() - b.mean()
    d = np.sqrt((a * a).sum() * (b * b).sum())
    return float((a * b).sum() / d) if d > 0 else float("nan")


def info(p, label):
    img = load(p)
    bar = blk(img, BART, H_EXP)
    dx, dy = energy(bar)
    g = bar.mean(axis=2)
    print(f"  {label:22s} bar mean={g.mean():7.2f} std={g.std():6.2f} "
          f"dx={dx:6.3f} dy={dy:6.3f} uniq={len(np.unique(bar.reshape(-1, 3), axis=0))}")
    return bar


cmd = sys.argv[1]

if cmd == "shift":
    # 底栏显示的是「上方 shift px 处」的内容吗？
    p, shift = sys.argv[2], int(sys.argv[3])
    img = load(p)
    bar = blk(img, BART, H_EXP)
    src = blk(img, BART - shift, H_EXP - shift)
    print(f"== shift test {p} shift={shift}")
    info(p, "bar")
    print(f"  corr(bar, src@+{shift}) = {corr(bar, src):+.4f}   "
          f"mean|diff| = {np.abs(bar - src).mean():.2f}")

elif cmd == "pair":
    a, b = sys.argv[2], sys.argv[3]
    print(f"== pair {a}  vs  {b}")
    ba = info(a, a.split('/')[-1])
    bb = info(b, b.split('/')[-1])
    print(f"  corr = {corr(ba, bb):+.4f}   mean|diff| = {np.abs(ba - bb).mean():.2f}")

elif cmd == "stats":
    for p in sys.argv[2:]:
        print(f"== stats {p}")
        info(p, p.split('/')[-1])
