"""底栏模糊验证：比较「底栏内的内容」与「同一内容滚出底栏后」的结构相关性。

思路（无需改动 App）：
  1. 在设置页截图 s1；
  2. 纵向滚动一段后再截 s2，s1 中位于底栏内的内容会滚到屏幕上方（变为清晰）；
  3. 用互相关求两次截图的垂直位移 sh；
  4. 取 s1 底栏区域内、且**避开三个导航图标/文字**的像素区，
     与 s2 中同一内容的清晰版本逐行去均值后求相关系数 r。

判据：
  r > 0.6  → 形状保留，说明没被模糊（只是半透明）
  r < 0.30 → 结构被抹平，说明真实高斯模糊已生效
  同时用 std 比值估算透过率，应接近 (1 - alpha)。
"""
import sys
import numpy as np
from PIL import Image

s1_path = sys.argv[1] if len(sys.argv) > 1 else "s1.png"
s2_path = sys.argv[2] if len(sys.argv) > 2 else "s2.png"

s1 = np.asarray(Image.open(s1_path).convert("RGB")).astype(np.float32)
s2 = np.asarray(Image.open(s2_path).convert("RGB")).astype(np.float32)
H, W, _ = s1.shape

# 底栏容器实际 bounds（本机 1440x3200，底栏 2885..3200）
BAR_Y0, BAR_Y1 = 2895, 3110
# 避开三个 NavigationBarItem 的图标与文字所在 x 区间（150-330 / 630-810 / 1110-1290）
X_RANGES = [(350, 610), (820, 1095)]


def row_hf(a):
    """逐行水平高频能量，用于求位移。"""
    reg = a[:, 350:610].mean(axis=2)
    return np.abs(np.diff(reg, axis=1)).mean(axis=1)


def find_shift(p1, p2, lo=500, hi=2700, max_shift=700):
    best_sh, best_c = 0, -9.0
    for sh in range(0, max_shift):
        a = p1[lo:hi]
        b = p2[lo - sh: hi - sh]
        n = min(len(a), len(b))
        if n < 500:
            continue
        c = np.corrcoef(a[:n], b[:n])[0, 1]
        if c > best_c:
            best_sh, best_c = sh, c
    return best_sh, best_c


p1, p2 = row_hf(s1), row_hf(s2)
sh, conf = find_shift(p1, p2)
print(f"垂直位移 sh = {sh}px   (相关系数 {conf:.3f})")
if conf < 0.9:
    print("!! 位移匹配不可靠，结果仅供参考")

bar = s1[BAR_Y0:BAR_Y1, :, :].mean(axis=2)          # 底栏内
clear = s2[BAR_Y0 - sh:BAR_Y1 - sh, :, :].mean(axis=2)  # 同一内容，已滚出底栏

def rowdem(e):
    return e - e.mean(axis=1, keepdims=True)

def hf(e):
    return np.abs(np.diff(e, axis=1)).mean()

all_a, all_b = [], []
for x0, x1 in X_RANGES:
    a = rowdem(bar[:, x0:x1])
    b = rowdem(clear[:, x0:x1])
    all_a.append(a.ravel())
    all_b.append(b.ravel())
    r = np.corrcoef(a.ravel(), b.ravel())[0, 1]
    ratio = hf(a) / hf(b) if hf(b) > 0 else 0
    print(f"  x[{x0},{x1})  r={r:+.3f}   hf比={ratio:.3f}")

A = np.concatenate(all_a)
B = np.concatenate(all_b)
R = np.corrcoef(A, B)[0, 1]
std_in = s1[BAR_Y0:BAR_Y1, 350:610].mean(axis=2).std()
std_out = s2[BAR_Y0 - sh:BAR_Y1 - sh, 350:610].mean(axis=2).std()

print()
print(f"总体结构相关性 r = {R:+.3f}")
print(f"透过率(std比)   = {std_in/std_out:.3f}")
print()
if R < 0.30:
    print("=> 结论：底栏区域结构已被抹平 —— 真实高斯模糊【已生效】")
elif R < 0.60:
    print("=> 结论：部分模糊（半径偏小或底色偏厚），效果不明显")
else:
    print("=> 结论：形状完整保留 —— 仍【没有模糊】，只是半透明")
