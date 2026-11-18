import math, sys
from PIL import Image, ImageDraw, ImageFilter
S = 4; N = 1024 * S
def px(v): return int(v * S)
img = Image.new("RGB", (N, N))
# background: deep navy, lighter toward the top-left
bg = Image.new("RGB", (N, N))
d = ImageDraw.Draw(bg)
for y in range(0, N, S):
    t = y / N
    d.rectangle([0, y, N, y + S], fill=(int(18 - 8 * t), int(26 - 12 * t), int(38 - 18 * t)))
img.paste(bg)
d = ImageDraw.Draw(img)
# faint scan grid
for i in range(0, 1024, 64):
    d.line([px(i), 0, px(i), N], fill=(24, 33, 46), width=px(1.5))
    d.line([0, px(i), N, px(i)], fill=(24, 33, 46), width=px(1.5))

GREEN = (63, 205, 96); DKGREEN = (34, 140, 62); AMBER = (240, 180, 40); WHITE = (238, 242, 246)
cx, cy, r = 390, 512, 300
mouth = 34  # half-angle, degrees

# barcode: bars marching into the mouth, left ones already inside it
bars = [(1, 14), (0, 10), (1, 30), (0, 12), (1, 12), (0, 22), (1, 22), (0, 10), (1, 12), (0, 14), (1, 34), (0, 12), (1, 14), (0, 20), (1, 20), (0, 10), (1, 12), (0, 16), (1, 26), (0, 10), (1, 12), (0, 12), (1, 18)]
x = 520; top, bot = 392, 632
bar_layer = Image.new("RGBA", (N, N), (0, 0, 0, 0))
bd = ImageDraw.Draw(bar_layer)
for on, w in bars:
    if on: bd.rounded_rectangle([px(x), px(top), px(x + w), px(bot)], radius=px(3), fill=WHITE + (255,))
    x += w
bar_end = x

# chomper body (pieslice leaves the mouth wedge open to the right)
body = Image.new("RGBA", (N, N), (0, 0, 0, 0))
bdy = ImageDraw.Draw(body)
bdy.pieslice([px(cx - r), px(cy - r), px(cx + r), px(cy + r)], mouth, 360 - mouth, fill=GREEN + (255,))
# shading: darker crescent at the bottom
shade = Image.new("RGBA", (N, N), (0, 0, 0, 0))
sd = ImageDraw.Draw(shade)
sd.pieslice([px(cx - r), px(cy - r), px(cx + r), px(cy + r)], mouth, 360 - mouth, fill=DKGREEN + (255,))
mask = Image.new("L", (N, N), 0)
md = ImageDraw.Draw(mask)
md.ellipse([px(cx - r - 40), px(cy - r - 110), px(cx + r + 40), px(cy + r - 70)], fill=255)
mask = mask.filter(ImageFilter.GaussianBlur(px(30)))
body = Image.composite(body, shade, mask)

# glow under the body
glow = Image.new("RGBA", (N, N), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse([px(cx - r - 10), px(cy - r - 10), px(cx + r + 10), px(cy + r + 10)], fill=(63, 205, 96, 70))
glow = glow.filter(ImageFilter.GaussianBlur(px(46)))
img.paste(glow, (0, 0), glow)

# bars only show outside the body or inside the mouth wedge: cut away what the body covers
img.paste(bar_layer, (0, 0), bar_layer)
img.paste(body, (0, 0), body)
d = ImageDraw.Draw(img)

# teeth: little white triangles along both jaws
def jaw(sign):
    a = math.radians(sign * mouth)
    for t in (0.42, 0.58, 0.74, 0.90):
        bx, by = cx + r * t * math.cos(a), cy + r * t * math.sin(a)
        ux, uy = math.cos(a), math.sin(a)
        nx, ny = -uy * -sign, ux * -sign          # into the mouth
        w, h = 20, 34
        d.polygon([(px(bx - ux * w), px(by - uy * w)), (px(bx + ux * w), px(by + uy * w)), (px(bx + nx * h), px(by + ny * h))], fill=WHITE)


# the eye is a camera lens
ex, ey = 362, 352
for rad, col in ((78, (12, 16, 22)), (62, (40, 52, 68)), (50, (10, 14, 20)), (34, (36, 86, 150)), (20, (8, 12, 18))):
    d.ellipse([px(ex - rad), px(ey - rad), px(ex + rad), px(ey + rad)], fill=col)
d.ellipse([px(ex - 30), px(ey - 34), px(ex - 8), px(ey - 12)], fill=(210, 232, 255))
d.ellipse([px(ex + 12), px(ey + 10), px(ex + 22), px(ey + 20)], fill=(120, 170, 230))

# HUD lock brackets around the part of the barcode still outside
bx0, by0, bx1, by1 = 704, 348, bar_end + 36, 676
L, W = 62, 14
for (x0, y0, dx, dy) in ((bx0, by0, 1, 1), (bx1, by0, -1, 1), (bx0, by1, 1, -1), (bx1, by1, -1, -1)):
    d.line([px(x0), px(y0), px(x0 + dx * L), px(y0)], fill=AMBER, width=px(W))
    d.line([px(x0), px(y0 - dy * W / 2), px(x0), px(y0 + dy * L)], fill=AMBER, width=px(W))

out = img.resize((1024, 1024), Image.LANCZOS)
out.save(sys.argv[1])
out.resize((180, 180), Image.LANCZOS).save(sys.argv[2])
out.resize((64, 64), Image.LANCZOS).save(sys.argv[3])
