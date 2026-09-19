import sys
import numpy as np
from PIL import Image
BART=2885; W=1440; PAD=20
def load(p): return np.asarray(Image.open(p).convert("RGB")).astype(np.float64)
for p in sys.argv[1:]:
    im=load(p); g=im.mean(axis=2)
    bar  = g[BART:3200, PAD:W-PAD]
    abov = g[BART-250:BART, PAD:W-PAD]
    dxb=np.abs(np.diff(bar,axis=1)).mean(); dxa=np.abs(np.diff(abov,axis=1)).mean()
    # 顶边跨界差：栏上方 10 行 vs 栏内第 10..20 行（按列平均）
    ra=g[BART-12:BART-2, PAD:W-PAD].mean(axis=0)
    rb=g[BART+8:BART+18, PAD:W-PAD].mean(axis=0)
    jump=np.abs(ra-rb).mean()
    # 基线：栏上方同宽度相邻两带的平均差
    r0=g[BART-32:BART-22, PAD:W-PAD].mean(axis=0)
    base=np.abs(r0-ra).mean()
    mag=((im[BART:3200,:,0]>200)&(im[BART:3200,:,1]<90)&(im[BART:3200,:,2]>200)).sum()
    print(f"== {p}")
    print(f"   dx above={dxa:6.3f}  bar={dxb:6.3f}  ratio={dxb/max(dxa,1e-6):5.2f}")
    print(f"   顶边跨界差={jump:6.3f}   栏上方基线差={base:6.3f}   洋红px={mag}")
    # 栏内亮度纵向趋势（分 4 段）
    seg=[bar[i*78:(i+1)*78].mean() for i in range(4)]
    print(f"   bar 4 段亮度: "+" ".join(f"{v:6.2f}" for v in seg))
