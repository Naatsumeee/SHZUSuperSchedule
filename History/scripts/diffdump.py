import sys
import numpy as np
from PIL import Image
BART=2885
def load(p): return np.asarray(Image.open(p).convert("RGB")).astype(np.float64)
for p in sys.argv[1:]:
    im=load(p); g=im.mean(axis=2)
    print(f"== {p}  WxH={im.shape[1]}x{im.shape[0]}")
    for y0,y1,lab in [(170,400,"top" ),(1200,1400,"mid"),(2570,2885,"above"),(2885,3200,"bar")]:
        b=g[y0:y1]; 
        print(f"   {lab:6s} y[{y0},{y1}) mean={b.mean():7.2f} std={b.std():6.2f} dx={np.abs(np.diff(b,axis=1)).mean():6.3f}")
    print("   bar row profile (mean/std):")
    for i in range(0,315,30):
        r=g[BART+i]
        print(f"     +{i:3d} mean={r.mean():7.2f} std={r.std():6.2f} min={r.min():5.0f} max={r.max():5.0f}")
