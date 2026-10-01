"""Generates the CubeLens launcher and splash vector drawables, plus an optional PNG preview.

The artwork is an isometric cube: three visible faces (U on top, F on the left, R on the right),
each with 3x3 rounded glossy stickers on a near-black rounded hexagonal body, over a sunset bloom
on ink. All geometry is in the 108x108 adaptive-icon viewport; the foreground stays inside the
66dp safe zone (radius 33 around the center).

Usage (from the repository root; needs numpy and Pillow):

    python3 tools/icon/gen_icons.py --res app/src/main/res [--preview /tmp/icon_preview.png]

Writes drawable/ic_launcher_foreground.xml, ic_launcher_background.xml,
ic_launcher_monochrome.xml and ic_splash.xml. The sticker mix and colors mirror CubeMark.kt and
CubePalette; keep them in sync when changing either side.
"""
import argparse
import math
import os

import numpy as np
from PIL import Image, ImageDraw

COL = {
    'W': (0xF4, 0xF4, 0xF7),
    'Y': (0xFF, 0xD5, 0x00),
    'G': (0x14, 0xC9, 0x5E),
    'B': (0x1F, 0x6B, 0xFF),
    'R': (0xF2, 0x24, 0x3C),
    'O': (0xFF, 0x7B, 0x00),
}

# Curated sticker mix (rows top->bottom as seen on that face, cols left->right as seen).
TOP = ["OWY",
       "RWW",
       "WBO"]
LEFT = ["YRG",
        "WGO",
        "GOR"]
RIGHT = ["RYB",
         "ORR",
         "YWO"]

# Face shading (multiplier on sticker color) to sell the 3D light from the top-left.
SHADE = {'top': 1.0, 'left': 0.86, 'right': 0.70}
BODY = {'top': (0x26, 0x24, 0x30), 'left': (0x15, 0x14, 0x1C), 'right': (0x0C, 0x0B, 0x11)}


def hexs(c):
    return '#%02X%02X%02X' % tuple(int(round(max(0, min(255, v)))) for v in c)


def hexa(c, a):
    return '#%02X%02X%02X%02X' % ((int(round(a * 255)),) + tuple(int(round(max(0, min(255, v)))) for v in c))


def shade(c, k):
    return tuple(v * k for v in c)


def lighten(c, k):
    return tuple(v + (255 - v) * k for v in c)


class Cube:
    def __init__(self, cx, cy, s):
        self.cx, self.cy, self.s = cx, cy, s
        h = math.cos(math.radians(30)) * s
        self.T = np.array([cx, cy - s])
        self.UR = np.array([cx + h, cy - s / 2])
        self.LR = np.array([cx + h, cy + s / 2])
        self.Bt = np.array([cx, cy + s])
        self.LL = np.array([cx - h, cy + s / 2])
        self.UL = np.array([cx - h, cy - s / 2])
        self.C = np.array([cx, cy])

    def face(self, name):
        """Origin (top-left as seen), u axis (cols), v axis (rows) for a face."""
        if name == 'top':
            return self.T, self.UR - self.T, self.UL - self.T
        if name == 'left':
            return self.UL, self.C - self.UL, self.LL - self.UL
        return self.C, self.UR - self.C, self.Bt - self.C

    def hexagon(self):
        return [self.T, self.UR, self.LR, self.Bt, self.LL, self.UL]

    def face_quad(self, name):
        o, u, v = self.face(name)
        return [o, o + u, o + u + v, o + v]


def rounded_path(pts, r):
    """SVG path of a polygon with corners rounded by quadratic curves of 'radius' r (absolute units)."""
    n = len(pts)
    d = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        a = p1 + (p0 - p1) / np.linalg.norm(p0 - p1) * r
        b = p1 + (p2 - p1) / np.linalg.norm(p2 - p1) * r
        d.append((a, p1, b))
    s = 'M%.2f,%.2f' % tuple(d[0][2])
    for i in range(1, n + 1):
        a, p, b = d[i % n]
        s += 'L%.2f,%.2f' % tuple(a)
        s += 'Q%.2f,%.2f %.2f,%.2f' % (p[0], p[1], b[0], b[1])
    return s + 'Z'


def rounded_poly(pts, r, steps=8):
    """Polyline approximation (for the PIL preview) of rounded_path."""
    n = len(pts)
    out = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        a = p1 + (p0 - p1) / np.linalg.norm(p0 - p1) * r
        b = p1 + (p2 - p1) / np.linalg.norm(p2 - p1) * r
        for t in np.linspace(0, 1, steps):
            q = (1 - t) ** 2 * a + 2 * (1 - t) * t * p1 + t ** 2 * b
            out.append(tuple(q))
    return out


def stickers(cube, name, gap=0.10, inset=0.05):
    o, u, v = cube.face(name)
    res = []
    for r in range(3):
        for c in range(3):
            # cell in [0,1]^2 face coords with a uniform inset from the body edge
            span = (1 - 2 * inset) / 3
            u0 = inset + c * span + span * gap / 2
            u1 = inset + (c + 1) * span - span * gap / 2
            v0 = inset + r * span + span * gap / 2
            v1 = inset + (r + 1) * span - span * gap / 2
            q = [o + u * u0 + v * v0, o + u * u1 + v * v0, o + u * u1 + v * v1, o + u * u0 + v * v1]
            res.append((r, c, q))
    return res


def face_colors(name):
    grid = {'top': TOP, 'left': LEFT, 'right': RIGHT}[name]
    return lambda r, c: COL[grid[r][c]]


def cube_shapes(cube, sticker_radius, body_radius):
    """List of (kind, pts, radius, fill, gradient) shapes, back to front."""
    shapes = []
    # Body faces (rounded hexagon split in three by the inner Y), drawn as one rounded hexagon
    # plus face overlays for shading.
    shapes.append(('body', cube.hexagon(), body_radius, BODY['left'], None))
    for name in ('top', 'left', 'right'):
        quad = cube.face_quad(name)
        shapes.append(('bodyface', quad, body_radius * 0.6, BODY[name], None))
    for name in ('top', 'left', 'right'):
        colf = face_colors(name)
        o, u, v = cube.face(name)
        for r, c, q in stickers(cube, name):
            base = shade(colf(r, c), SHADE[name])
            hi = lighten(base, 0.28)
            lo = shade(base, 0.92)
            # gradient across sticker from its top-left corner to its bottom-right corner
            shapes.append(('sticker', q, sticker_radius, base, (q[0], q[2], hi, lo)))
    return shapes


# ---------------------------------------------------------------- preview raster
SS = 4


def render(size, layers):
    """layers: list of callables(draw_img_float) -> composite; simple painter with numpy."""
    W = size * SS
    img = np.zeros((W, W, 4), dtype=np.float32)
    for layer in layers:
        layer(img, W)
    out = (np.clip(img, 0, 1) * 255).astype(np.uint8)
    return Image.fromarray(out, 'RGBA').resize((size, size), Image.LANCZOS)


def over(img, rgb, alpha):
    a = alpha[..., None]
    img[..., :3] = img[..., :3] * (1 - a) + np.array(rgb)[None, None, :] * a if np.ndim(rgb) == 1 else img[..., :3] * (1 - a) + rgb * a
    img[..., 3:4] = img[..., 3:4] * (1 - a) + a


def poly_mask(W, pts, scale):
    m = Image.new('L', (W, W), 0)
    ImageDraw.Draw(m).polygon([(x * scale, y * scale) for x, y in pts], fill=255)
    return np.asarray(m, dtype=np.float32) / 255


def shapes_layer(shapes):
    def f(img, W):
        scale = W / 108
        yy, xx = np.mgrid[0:W, 0:W].astype(np.float32) / scale
        for kind, pts, r, fill, grad in shapes:
            m = poly_mask(W, rounded_poly(pts, r), scale)
            if grad is None:
                over(img, np.array(fill) / 255, m)
            else:
                p0, p1, c0, c1 = grad
                d = p1 - p0
                t = ((xx - p0[0]) * d[0] + (yy - p0[1]) * d[1]) / (d @ d)
                t = np.clip(t, 0, 1)[..., None]
                rgb = (np.array(c0)[None, None, :] * (1 - t) + np.array(c1)[None, None, :] * t) / 255
                over(img, rgb, m)
    return f


def rim_layer(cube, r):
    def f(img, W):
        scale = W / 108
        _, pts = rim_path(cube, r)
        m = Image.new('L', (W, W), 0)
        ImageDraw.Draw(m).line([(x * scale, y * scale) for x, y in rounded_open(pts, r)], fill=255, width=max(1, int(0.6 * scale)), joint='curve')
        over(img, np.array([1.0, 1.0, 1.0]), np.asarray(m, np.float32) / 255 * 0.22)
    return f


def rounded_open(pts, r, steps=8):
    out = [tuple(pts[0])]
    for i in range(1, len(pts) - 1):
        p0, p1, p2 = pts[i - 1], pts[i], pts[i + 1]
        a = p1 + (p0 - p1) / np.linalg.norm(p0 - p1) * r
        b = p1 + (p2 - p1) / np.linalg.norm(p2 - p1) * r
        for t in np.linspace(0, 1, steps):
            out.append(tuple((1 - t) ** 2 * a + 2 * (1 - t) * t * p1 + t ** 2 * b))
    out.append(tuple(pts[-1]))
    return out


def radial_layer(cx, cy, rad, stops, sx=1.0, sy=1.0):
    """stops: list of (pos, rgb, alpha)."""
    def f(img, W):
        scale = W / 108
        yy, xx = np.mgrid[0:W, 0:W].astype(np.float32) / scale
        d = np.sqrt(((xx - cx) / sx) ** 2 + ((yy - cy) / sy) ** 2) / rad
        d = np.clip(d, 0, 1)
        pos = [p for p, _, _ in stops]
        rgb = np.stack([np.interp(d, pos, [c[i] / 255 for _, c, _ in stops]) for i in range(3)], -1)
        a = np.interp(d, pos, [al for _, _, al in stops])
        over(img, rgb, a)
    return f


def fill_layer(rgb):
    def f(img, W):
        over(img, np.array(rgb) / 255, np.ones((W, W), np.float32))
    return f


# ---------------------------------------------------------------- XML emitters
def vector_header(w=108, h=108):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{w}dp"\n    android:height="{h}dp"\n'
            f'    android:viewportWidth="{w}"\n    android:viewportHeight="{h}">\n')


def xml_path(d, fill=None, grad=None, alpha=None):
    if grad is None:
        a = '' if alpha is None else f' android:fillAlpha="{alpha}"'
        return f'    <path android:fillColor="{fill}"{a} android:pathData="{d}" />\n'
    return (f'    <path android:pathData="{d}">\n'
            '        <aapt:attr name="android:fillColor">\n'
            f'{grad}'
            '        </aapt:attr>\n'
            '    </path>\n')


def xml_linear(p0, p1, c0, c1):
    return (f'            <gradient android:type="linear" android:startX="{p0[0]:.2f}" android:startY="{p0[1]:.2f}"'
            f' android:endX="{p1[0]:.2f}" android:endY="{p1[1]:.2f}"'
            f' android:startColor="{hexs(c0)}" android:endColor="{hexs(c1)}" />\n')


def xml_radial(cx, cy, r, stops):
    s = (f'            <gradient android:type="radial" android:centerX="{cx:.2f}" android:centerY="{cy:.2f}"'
         f' android:gradientRadius="{r:.2f}">\n')
    for pos, c, a in stops:
        s += f'                <item android:offset="{pos:.3f}" android:color="{hexa(c, a)}" />\n'
    return s + '            </gradient>\n'


def rim_path(cube, r):
    # Upper silhouette edges, pulled in by the body corner radius so it hugs the rounded body.
    pts = [cube.UL + (cube.LL - cube.UL) * 0.35, cube.UL, cube.T, cube.UR, cube.UR + (cube.LR - cube.UR) * 0.35]
    d = 'M%.2f,%.2f' % tuple(pts[0])
    for i in range(1, 4):
        p0, p1, p2 = pts[i - 1], pts[i], pts[i + 1]
        a = p1 + (p0 - p1) / np.linalg.norm(p0 - p1) * r
        b = p1 + (p2 - p1) / np.linalg.norm(p2 - p1) * r
        d += 'L%.2f,%.2fQ%.2f,%.2f %.2f,%.2f' % (a[0], a[1], p1[0], p1[1], b[0], b[1])
    d += 'L%.2f,%.2f' % tuple(pts[4])
    return d, pts


def cube_xml(shapes, comment, cube=None, body_radius=3.0):
    s = f'    <!-- {comment} -->\n'
    for kind, pts, r, fill, grad in shapes:
        d = rounded_path(pts, r)
        if grad is None:
            s += xml_path(d, fill=hexs(fill))
        else:
            p0, p1, c0, c1 = grad
            s += xml_path(d, grad=xml_linear(p0, p1, c0, c1))
    if cube is not None:
        d, _ = rim_path(cube, body_radius)
        s += ('    <!-- Rim light on the upper silhouette. -->\n'
              f'    <path android:strokeColor="#38FFFFFF" android:strokeWidth="0.6" android:strokeLineCap="round"'
              f' android:strokeLineJoin="round" android:pathData="{d}" />\n')
    return s


# ---------------------------------------------------------------- the assets
INK = (0x0A, 0x0A, 0x10)
MAGENTA = (0xFF, 0x2E, 0x63)
TANGERINE = (0xFF, 0x7A, 0x18)
GOLD = (0xFF, 0xC9, 0x3C)
MINT = (0x2E, 0xE6, 0xA6)

# Background glow: warm sunset bloom behind the cube, plus a faint magenta wash top-left.
BG_GLOWS = [
    # (cx, cy, radius, stops)
    (54, 74, 62, [(0.0, GOLD, 0.95), (0.22, TANGERINE, 0.85), (0.5, MAGENTA, 0.55), (0.78, MAGENTA, 0.16), (1.0, MAGENTA, 0.0)]),
    (14, 10, 50, [(0.0, MAGENTA, 0.20), (1.0, MAGENTA, 0.0)]),
    (100, 14, 36, [(0.0, MINT, 0.10), (1.0, MINT, 0.0)]),
]

# Circumradius 28 leaves ~5dp inside the 33dp safe-zone radius, so the cube breathes in a circle mask.
FG_CUBE = Cube(54, 54.5, 28)
SPLASH_CUBE = Cube(54, 54.5, 23.5)


def gen_foreground():
    sh = cube_shapes(FG_CUBE, sticker_radius=1.5, body_radius=3.2)
    x = vector_header()
    x += cube_xml(sh, 'Isometric cube: U on top, F on the left, R on the right; 3x3 glossy stickers.', FG_CUBE, 3.2)
    x += '</vector>\n'
    return x, sh


def gen_background():
    x = vector_header()
    x += f'    <path android:fillColor="{hexs(INK)}" android:pathData="M0,0h108v108h-108z" />\n'
    for cx, cy, r, stops in BG_GLOWS:
        x += xml_path(f'M0,0h108v108h-108z', grad=xml_radial(cx, cy, r, stops))
    x += '</vector>\n'
    return x


# Opacity per face in the themed (monochrome) icon, so the tinted cube keeps its volume.
MONO_ALPHA = {'top': 1.0, 'left': 0.75, 'right': 0.5}


def gen_monochrome():
    # The 27 visible stickers, shaded per face; the system tints the result.
    x = vector_header()
    for name in ('top', 'left', 'right'):
        x += f'    <!-- {name.capitalize()} face. -->\n'
        for _, _, q in stickers(FG_CUBE, name):
            x += xml_path(rounded_path(q, 1.5), fill='#FFFFFFFF', alpha=MONO_ALPHA[name])
    x += '</vector>\n'
    return x


SPLASH_GLOW = (54, 57, 34.5, [(0.0, GOLD, 0.95), (0.45, TANGERINE, 0.75), (0.72, MAGENTA, 0.38), (0.9, MAGENTA, 0.08), (1.0, MAGENTA, 0.0)])


def gen_splash():
    sh = cube_shapes(SPLASH_CUBE, sticker_radius=1.2, body_radius=2.6)
    x = vector_header()
    cx, cy, r, stops = SPLASH_GLOW
    x += '    <!-- Sunset glow; fades out before the 2/3 circular splash mask (radius 36). -->\n'
    x += xml_path(f'M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0,1 0,{2 * r:.2f},0a{r:.2f},{r:.2f} 0,1 0,{-2 * r:.2f},0Z',
                  grad=xml_radial(cx, cy, r, stops))
    x += cube_xml(sh, 'Brand cube, sized to sit inside the splash glow.', SPLASH_CUBE, 2.6)
    x += '</vector>\n'
    return x, sh


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--res', help='Android res directory to write the drawables into')
    parser.add_argument('--preview', help='PNG path for a raster preview (icon, circle mask, splash)')
    args = parser.parse_args()

    fg_xml, fg = gen_foreground()
    bg_xml = gen_background()
    mono_xml = gen_monochrome()
    sp_xml, sp = gen_splash()
    if args.res:
        d = os.path.join(args.res, 'drawable')
        for name, content in [('ic_launcher_foreground.xml', fg_xml), ('ic_launcher_background.xml', bg_xml),
                              ('ic_launcher_monochrome.xml', mono_xml), ('ic_splash.xml', sp_xml)]:
            with open(os.path.join(d, name), 'w') as f:
                f.write(content)
            print('wrote', name, len(content))
    if not args.preview:
        return
    layers = [fill_layer(INK)] + [radial_layer(cx, cy, r, st) for cx, cy, r, st in BG_GLOWS] + [shapes_layer(fg), rim_layer(FG_CUBE, 3.2)]
    full = render(432, layers)
    # circle-masked at 192 px (visible 72/108 of layer)
    crop = full.crop((72, 72, 360, 360)).resize((192, 192), Image.LANCZOS)
    mask = Image.new('L', (192 * 4, 192 * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, 192 * 4 - 1, 192 * 4 - 1), fill=255)
    mask = mask.resize((192, 192), Image.LANCZOS)
    canvas = Image.new('RGBA', (640, 432), (40, 44, 52, 255))
    canvas.paste(full, (0, 0))
    canvas.paste(crop, (440, 20), mask)
    sp = render(432, [fill_layer(INK), radial_layer(*SPLASH_GLOW[:3], SPLASH_GLOW[3]), shapes_layer(sp), rim_layer(SPLASH_CUBE, 2.6)])
    canvas2 = Image.new('RGBA', (640 + 432, 432), (0, 0, 0, 255))
    canvas2.paste(canvas, (0, 0))
    canvas2.paste(sp, (640, 0))
    canvas2.save(args.preview)
    print('preview saved to', args.preview)


if __name__ == '__main__':
    main()
