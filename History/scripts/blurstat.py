import sys
import numpy as np
from PIL import Image

path = sys.argv[1]
BART = int(sys.argv[2]) if len(sys.argv) > 2 else 2885

img = np.asarray(Image.open(path).convert("RGB")).astype(np.int32)
H, W, _ = img.shape
print(f"== {path}  {W}x{H}  barTop={BART}")

def stats(y0, y1, label):
    blk = img[y0:y1]
    g = blk.mean(axis=2)
    dx = np.abs(np.diff(g, axis=1)).mean()
    dy = np.abs(np.diff(g, axis=0)).mean()
    uniq = len(np.unique(blk.reshape(-1, 3), axis=0))
    print(f"  {label} y[{y0},{y1}) mean={g.mean():7.2f} std={g.std():6.2f} "
          f"dx={dx:6.3f} dy={dy:6.3f} uniq={uniq}")

h = H - BART
stats(max(0, BART - h), BART, "above")
stats(BART, H, "bar  ")

bar = img[BART:H]
mag = ((bar[:, :, 0] > 200) & (bar[:, :, 1] < 90) & (bar[:, :, 2] > 200)).sum()
print(f"  magenta px in bar: {mag} / {(H - BART) * W}")

g = bar.mean(axis=2)
print("  row profile:")
for i in range(0, g.shape[0], 40):
    row = g[i]
    print(f"    row+{i:4d}  mean={row.mean():7.2f} std={row.std():6.2f} "
          f"min={row.min():6.1f} max={row.max():6.1f}")
