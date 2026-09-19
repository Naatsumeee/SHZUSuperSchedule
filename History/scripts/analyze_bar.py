from PIL import Image
import numpy as np
im = Image.open('shot_week.png').convert('RGB')
a = np.asarray(im).astype(np.float32)
H, W, _ = a.shape
print('shape', a.shape)

def stats(name, y0, y1, x0=0, x1=W):
    reg = a[y0:y1, x0:x1]
    gray = reg.mean(axis=2)
    gx = np.abs(np.diff(gray, axis=1)).mean()
    gy = np.abs(np.diff(gray, axis=0)).mean()
    sat = (reg.max(axis=2) - reg.min(axis=2)).mean()
    print(f'{name:22s} mean={gray.mean():6.1f} std={gray.std():6.2f} hf_x={gx:5.2f} hf_y={gy:5.2f} sat={sat:5.2f}')

# 底栏容器 bounds = [0,2885][1440,3200]
stats('content_sharp(2500-2850)', 2500, 2850)
stats('bar_top(2890-2960)', 2890, 2960)
stats('bar_mid(2960-3060)', 2960, 3060)
stats('bar_bottom(3060-3120)', 3060, 3120)
stats('gesture_zone(3130-3200)', 3130, 3200)

# 底栏区域内是否存在水平方向的色块差异（说明有内容透出来）
bar = a[2890:3120]
print('\nbar row means (每 10 行):')
for i in range(0, bar.shape[0], 20):
    row = bar[i]
    print(f'  y={2890+i}: mean={row.mean():6.1f} std_over_x={row.std(axis=0).mean():5.2f}')

# 裁剪对比图：上=内容区(清晰) 下=底栏区
crop = im.crop((0, 2500, 1440, 3200))
crop.save('cmp_bar.png')
print('\nsaved cmp_bar.png')
