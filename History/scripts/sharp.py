"""底栏模糊判定：只依赖 PIL，算各区域的水平梯度能量（局部细节量）。

用法: python sharp.py a.png [b.png ...]
对每张图输出三个区域的 mean|dI/dx|：
  BAR   底栏内部（x 350..610，避开三个导航图标），y 2900..3100
  ABOVE 底栏上方同宽区域，y 2560..2760
  MID   屏幕中部参考，y 1200..1400
"""
import sys
from PIL import Image

REGIONS = {
    "BAR": (350, 2900, 610, 3100),
    "ABOVE": (350, 2560, 610, 2760),
    "MID": (350, 1200, 610, 1400),
}


def grad_energy(im, box, step=2):
    x0, y0, x1, y1 = box
    px = im.load()
    tot, n = 0.0, 0
    for y in range(y0, y1, step):
        prev = sum(px[x0, y]) / 3.0
        for x in range(x0 + step, x1, step):
            cur = sum(px[x, y]) / 3.0
            tot += abs(cur - prev)
            n += 1
            prev = cur
    return tot / max(n, 1)


for path in sys.argv[1:]:
    im = Image.open(path).convert("RGB")
    out = [f"{path}  size={im.size}"]
    for name, box in REGIONS.items():
        out.append(f"  {name:5s} 梯度能量={grad_energy(im, box):7.3f}")
    print("\n".join(out))
