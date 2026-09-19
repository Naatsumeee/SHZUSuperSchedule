from PIL import Image
import numpy as np
s1 = np.asarray(Image.open('s1.png').convert('RGB')).astype(np.float32)
s2 = np.asarray(Image.open('s2.png').convert('RGB')).astype(np.float32)
X0,X1 = 350,611
def prof(a):
    reg = a[:, X0:X1].mean(axis=2)
    return np.abs(np.diff(reg,axis=1)).mean(axis=1)
p1,p2 = prof(s1), prof(s2)
lo,hi = 500,2700
best=(0,-9)
for sh in range(0,700):
    a=p1[lo:hi]; b=p2[lo-sh:hi-sh]
    n=min(len(a),len(b))
    if n<500: continue
    c=np.corrcoef(a[:n],b[:n])[0,1]
    if c>best[1]: best=(sh,c)
sh,c = best
print(f'垂直位移 sh={sh}px  相关系数={c:.3f}')
thr = np.percentile(p1[lo:hi], 70)
ys = [y for y in range(2400,2880) if p1[y] > thr]
print(f's1 底栏上方文本行数={len(ys)} 高hf阈值={thr:.2f}')
if ys:
    pairs=[(y,y-sh) for y in ys if 2885 <= y-sh <= 3120]
    print(f'其中滚动后落入底栏区的行数={len(pairs)}')
    if pairs:
        a=np.mean([p1[y] for y,_ in pairs]); b=np.mean([p2[t] for _,t in pairs])
        print(f'  s1 底栏外(清晰) 平均hf = {a:.3f}')
        print(f'  s2 底栏内(?)   平均hf = {b:.3f}')
        print(f'  比值 = {b/a:.3f}   (>0.6 说明未模糊 / <0.25 说明已模糊)')
