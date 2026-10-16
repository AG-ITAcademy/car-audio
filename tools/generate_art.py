"""Generates the Nightline mood tiles and Android Auto tab icons.

Usage: python3 tools/generate_art.py app/src/main/assets/art
Then convert the mood_*.png files to JPEG (quality 88) to keep the APK small.
"""
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1]
S = 512          # tile size
SS = 2           # supersampling

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
def hexc(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def gradient(c1, c2, size):
    """Diagonal gradient with a soft radial light in the top-left corner."""
    img = Image.new('RGB', (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size)
            r = math.hypot(x - size * 0.2, y - size * 0.15) / (size * 1.1)
            base = lerp(c1, c2, t)
            glow = max(0.0, 1.0 - r) ** 2 * 0.35
            px[x, y] = tuple(min(255, int(v + (255 - v) * glow)) for v in base)
    return img

def grain(img, amount=10, seed=1):
    rnd = random.Random(seed)
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            n = rnd.randint(-amount, amount)
            r, g, b = px[x, y]
            px[x, y] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)))
    return img

def glyph_layer(draw_fn, size):
    """White glyph on transparent, with a soft glow."""
    big = size * SS
    layer = Image.new('L', (big, big), 0)
    draw_fn(ImageDraw.Draw(layer), big)
    layer = layer.resize((size, size), Image.LANCZOS)
    glow = layer.filter(ImageFilter.GaussianBlur(size * 0.04))
    return layer, glow

def compose(c1, c2, draw_fn, seed):
    img = grain(gradient(hexc(c1), hexc(c2), S), 6, seed).convert('RGBA')
    mask, glow = glyph_layer(draw_fn, S)
    white = Image.new('RGBA', (S, S), (255, 255, 255, 255))
    img.paste(Image.new('RGBA', (S, S), (255, 255, 255, 90)), (0, 0), glow)
    img.paste(white, (0, 0), mask.point(lambda v: int(v * 0.92)))
    # road lines at the bottom: the Nightline signature
    road = Image.new('L', (S * SS, S * SS), 0)
    d = ImageDraw.Draw(road)
    w = S * SS
    for i in range(5):
        y0 = int(w * (0.84 + i * 0.035)); y1 = y0 + int(w * 0.012)
        x0 = int(w * 0.5 - (i + 1) * w * 0.012); x1 = int(w * 0.5 + (i + 1) * w * 0.012)
        d.rectangle([x0, y0, x1, y1], fill=170 - i * 20)
    road = road.resize((S, S), Image.LANCZOS)
    img.paste(Image.new('RGBA', (S, S), (255, 255, 255, 255)), (0, 0), road)
    return img.convert('RGB')

# ---------- glyphs (drawn at size w, white = 255) ----------
def sun(d, w):
    c = w / 2; r = w * 0.15
    d.ellipse([c - r, c * 0.86 - r, c + r, c * 0.86 + r], fill=255)
    for k in range(12):
        a = k * math.pi / 6
        r1, r2 = w * 0.22, w * 0.30
        x1, y1 = c + r1 * math.cos(a), c * 0.86 + r1 * math.sin(a)
        x2, y2 = c + r2 * math.cos(a), c * 0.86 + r2 * math.sin(a)
        d.line([x1, y1, x2, y2], fill=255, width=int(w * 0.03))

def bolt(d, w):
    p = [(0.56, 0.16), (0.32, 0.52), (0.48, 0.52), (0.42, 0.80), (0.68, 0.40), (0.52, 0.40), (0.60, 0.16)]
    d.polygon([(x * w, y * w) for x, y in p], fill=255)

def moon(d, w):
    c = w * 0.48; r = w * 0.24
    d.ellipse([c - r, c - r, c + r, c + r], fill=255)
    o = w * 0.11
    d.ellipse([c - r + o * 1.2, c - r - o * 0.4, c + r + o * 1.2, c + r - o * 0.4], fill=0)
    for (sx, sy, sr) in [(0.72, 0.24, 0.018), (0.78, 0.42, 0.012), (0.64, 0.14, 0.01)]:
        d.ellipse([sx * w - sr * w, sy * w - sr * w, sx * w + sr * w, sy * w + sr * w], fill=255)

def star(d, w):
    c = w / 2; cy = w * 0.45
    pts = []
    for k in range(10):
        a = -math.pi / 2 + k * math.pi / 5
        r = w * (0.26 if k % 2 == 0 else 0.11)
        pts.append((c + r * math.cos(a), cy + r * math.sin(a)))
    d.polygon(pts, fill=255)

def wave(d, w):
    for row in range(3):
        pts = []
        for i in range(101):
            x = w * (0.18 + 0.64 * i / 100)
            y = w * (0.32 + row * 0.13) + math.sin(i / 100 * math.pi * 3 + row) * w * 0.04
            pts.append((x, y))
        d.line(pts, fill=255 - row * 60, width=int(w * 0.035), joint='curve')

def rings(d, w):
    c = w / 2; cy = w * 0.45
    for k, r in enumerate([0.28, 0.19, 0.10]):
        rr = w * r
        d.ellipse([c - rr, cy - rr, c + rr, cy + rr], outline=255, width=int(w * 0.03))
    d.ellipse([c - w * 0.035, cy - w * 0.035, c + w * 0.035, cy + w * 0.035], fill=255)

def heart_note(d, w):
    # a music note
    d.ellipse([w * 0.30, w * 0.52, w * 0.46, w * 0.66], fill=255)
    d.rectangle([w * 0.43, w * 0.18, w * 0.47, w * 0.60], fill=255)
    d.polygon([(w * 0.47, w * 0.18), (w * 0.70, w * 0.26), (w * 0.70, w * 0.34), (w * 0.47, w * 0.27)], fill=255)
    d.ellipse([w * 0.56, w * 0.44, w * 0.72, w * 0.58], fill=255)
    d.rectangle([w * 0.67, w * 0.26, w * 0.71, w * 0.52], fill=255)

def cassette(d, w):
    d.rounded_rectangle([w * 0.2, w * 0.28, w * 0.8, w * 0.64], radius=w * 0.05, outline=255, width=int(w * 0.03))
    for cx in (0.38, 0.62):
        r = w * 0.06
        d.ellipse([cx * w - r, w * 0.43 - r, cx * w + r, w * 0.43 + r], outline=255, width=int(w * 0.025))
    d.line([w * 0.32, w * 0.58, w * 0.68, w * 0.58], fill=255, width=int(w * 0.02))

MOODS = {
    'morning':   ('#FFB224', '#FF5F6D', sun),
    'highway':   ('#E53950', '#5B2A86', bolt),
    'night':     ('#1B1F5E', '#16C5D8', moon),
    'eighties':  ('#FF3CAC', '#FF9A3C', cassette),
    'chill':     ('#0FA3A3', '#5BD68A', wave),
    'focus':     ('#2B3445', '#3D7EAA', rings),
    'romania':   ('#002B7F', '#CE1126', star),
    'throwback': ('#7F00FF', '#E100FF', heart_note),
}

for i, (name, (c1, c2, fn)) in enumerate(MOODS.items()):
    compose(c1, c2, fn, i).save(f'{OUT}/mood_{name}.png', optimize=True)

# ---------- tab icons: white on transparent, 128 px ----------
def tab_icon(name, fn):
    big = 128 * 4
    m = Image.new('L', (big, big), 0)
    fn(ImageDraw.Draw(m), big)
    m = m.resize((128, 128), Image.LANCZOS)
    out = Image.new('RGBA', (128, 128), (255, 255, 255, 0))
    out.putalpha(m)
    out.save(f'{OUT}/tab_{name}.png', optimize=True)

def tab_moods(d, w):
    g = w * 0.08
    for (x, y) in [(0, 0), (1, 0), (0, 1), (1, 1)]:
        x0 = w * 0.14 + x * (w * 0.36 + g); y0 = w * 0.14 + y * (w * 0.36 + g)
        d.rounded_rectangle([x0, y0, x0 + w * 0.32, y0 + w * 0.32], radius=w * 0.08, fill=255)

def tab_recent(d, w):
    c = w / 2; r = w * 0.36
    d.ellipse([c - r, c - r, c + r, c + r], outline=255, width=int(w * 0.08))
    d.line([c, c, c, c - r * 0.6], fill=255, width=int(w * 0.08))
    d.line([c, c, c + r * 0.45, c + r * 0.3], fill=255, width=int(w * 0.08))

def tab_search(d, w):
    c = w * 0.42; r = w * 0.26
    d.ellipse([c - r, c - r, c + r, c + r], outline=255, width=int(w * 0.09))
    d.line([c + r * 0.7, c + r * 0.7, w * 0.86, w * 0.86], fill=255, width=int(w * 0.11))

tab_icon('moods', tab_moods)
tab_icon('recent', tab_recent)
tab_icon('search', tab_search)
print('ok')
