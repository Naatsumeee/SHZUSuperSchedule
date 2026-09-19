import sys
from PIL import Image
BART=2885
for p in sys.argv[1:]:
    im = Image.open(p).convert("RGB")
    W,H = im.size
    bar = im.crop((0, BART, W, H))
    ctx = im.crop((0, BART-315, W, H))
    out = p.replace(".png","_crop.png")
    # 上下拼：上=栏正上方同高区域(参考清晰度)，下=底栏
    canvas = Image.new("RGB",(W, 315*2))
    canvas.paste(im.crop((0, BART-315, W, BART)), (0,0))
    canvas.paste(bar, (0,315))
    canvas.save(out)
    print("saved", out, canvas.size)
