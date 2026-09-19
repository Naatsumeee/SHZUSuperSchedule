from PIL import Image
import numpy as np
s1 = np.asarray(Image.open('s1.png').convert('RGB')).astype(np.float32)
s2 = np.asarray(Image.open('s2.png').convert('RGB')).astype(np.float32)
SH = 311
X0,X1 = 350,611           # 避开三个底栏图标/label 的 x 区间
Y0,Y1 = 2895,3110         # 底栏区域内、且无图标无 label

bar_in = s1[Y0:Y1, X0:X1].mean(axis=2)          # 底栏内（被磨砂层覆盖）
clear  = s2[Y0-SH:Y1-SH, X0:X1].mean(axis=2)    # 同一内容，滚出底栏后（清晰）

def rowdem(e):
    return e - e.mean(axis=1, keepdims=True)

a, b = rowdem(bar_in), rowdem(clear)

def hf(e):
    return np.abs(np.diff(e, axis=1)).mean()

print("== 绝对高频能量 ==")
print(f"  清晰内容 (s2, 底栏外) hf = {hf(b):.3f}")
print(f"  底栏内   (s1)          hf = {hf(a):.3f}")
print(f"  比值 = {hf(a)/hf(b):.3f}")

print("\n== 结构相关性（逐行去均值，只看 x 方向形状）==")
r = np.corrcoef(a.ravel(), b.ravel())[0,1]
print(f"  Pearson r = {r:.3f}")
print("  判据: r>0.6 形状保留(未模糊) / r<0.30 结构被抹平(已模糊)")

print("\n== 对比度（透过率）==")
print(f"  清晰 std = {b.std():.2f}")
print(f"  底栏内 std = {a.std():.2f}  → 透过率 ≈ {a.std()/b.std():.3f}")
