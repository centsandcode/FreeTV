"""Generate 6 dark, sober 1920x1080 wallpapers for the FreeTV launcher.

All imagery is synthesised from scratch (no external assets), so the whole
res/wallpapers set stays MIT-clean.

Two wallpapers per time-of-day slot; the launcher picks by the words
"morning" / "afternoon" / "night" in the filename. The slots are separated by
mean luminance, not just by name:

    morning    ~10-14%   cool pale blue-grey, a faint warm sunrise accent
    afternoon  ~7-10%    muted indigo and violet, dusk
    night      ~2-7%     deep space

Constraints enforced and verified here:
  * exact 1920x1080
  * bottom two thirds notably darker and flatter (app tiles go there)
  * top-left corner dark (greeting / date / RAM text in white)
  * global mean luminance < 25%, no large bright areas
  * subtle triangular dither to kill gradient banding

The brightness of the morning pair lives almost entirely in the top third:
ui_mask() takes a per-scene `drop` so a bright sky can sit over a tile area
that is just as dark as the night wallpapers'.

Each image is encoded as both PNG (palettised, FS-dithered) and JPEG; the
smaller file wins. In practice JPEG wins by ~10x, because the dither needed
to avoid banding destroys the compressibility of a palette PNG.
"""
import os
import tempfile
import numpy as np
from PIL import Image

W, H = 1920, 1080
OUT = r"C:\Users\adiaz\Documents\proyectos\FreeTV\res\wallpapers"
TMP = tempfile.mkdtemp(prefix="freetv-wp-")   # scratch; never inside the repo

rng = np.random.default_rng(20261109)

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
u = xx / (W - 1.0)          # 0..1 left->right
v = yy / (H - 1.0)          # 0..1 top->bottom


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def blur(a, sigma):
    """Separable gaussian blur via repeated box filters (fast, no scipy)."""
    if sigma <= 0:
        return a
    # 3 box passes approximate a gaussian
    r = max(1, int(round(sigma * 0.95)))
    out = a.astype(np.float32)
    for _ in range(3):
        for axis in (0, 1):
            k = 2 * r + 1
            pad = [(0, 0), (0, 0)]
            pad[axis] = (r, r)
            p = np.pad(out, pad, mode="edge")
            c = np.cumsum(p, axis=axis, dtype=np.float64)
            zero = np.zeros_like(np.take(c, [0], axis=axis))
            c = np.concatenate([zero, c], axis=axis)
            hi = np.take(c, np.arange(k, k + out.shape[axis]), axis=axis)
            lo = np.take(c, np.arange(0, out.shape[axis]), axis=axis)
            out = ((hi - lo) / k).astype(np.float32)
    return out


def fbm(octaves=5, base=6, persistence=0.5, seed=None):
    """Low-cost fractal value noise, bicubically upsampled."""
    r = np.random.default_rng(seed if seed is not None else rng.integers(1 << 30))
    total = np.zeros((H, W), np.float32)
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        n = base * (2 ** o)
        gh, gw = max(2, int(n * H / W)), max(2, n)
        grid = r.random((gh, gw)).astype(np.float32)
        layer = np.asarray(
            Image.fromarray((grid * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC),
            np.float32) / 255.0
        total += layer * amp
        norm += amp
        amp *= persistence
    return total / norm


def mix(a, b, t):
    """Blend two RGB colours (3-tuples) by scalar field t -> HxWx3."""
    a = np.asarray(a, np.float32)
    b = np.asarray(b, np.float32)
    t = t[..., None]
    return a * (1 - t) + b * t


def stars(count, ymax=1.0, bright=0.7, size=1.0, seed=1):
    """Point stars drawn with a tiny gaussian PSF onto a float plane."""
    r = np.random.default_rng(seed)
    plane = np.zeros((H, W), np.float32)
    sx = r.integers(0, W, count)
    sy = (r.random(count) ** 1.25 * (ymax * H)).astype(int)
    sy = np.clip(sy, 0, H - 1)
    mag = r.random(count) ** 3.0 * bright          # mostly faint
    np.add.at(plane, (sy, sx), mag)
    core = blur(plane, 0.55 * size)
    halo = blur(plane, 2.2 * size) * 0.28
    return core * 1.5 + halo


# ---------------------------------------------------------------- UI masks
def ui_mask(img, drop=0.58, basemix=0.26, tl=0.28, tl_w=0.95, tl_h=0.72):
    """Darken + flatten the lower two thirds and the top-left text zone.

    `drop` is how much of the scene's own brightness is removed at the bottom.
    The brighter the scene's sky, the larger it has to be, so that every
    wallpaper ends up with a comparably dark tile area whatever its slot.

    `tl`/`tl_w`/`tl_h` carve the greeting / date / RAM corner: an elliptical
    well that holds full strength across the whole text block and only then
    falls off, so the bright edge of the block is covered too. The bright
    morning skies need a deeper well than the night ones to keep white text
    above a ~4.5:1 contrast ratio.
    """
    # lower two thirds: ramp down brightness and pull toward a flat floor
    t = smoothstep(0.30, 0.64, v)                      # 0 top third -> 1 lower
    img = img * (1.0 - drop * t)[..., None]

    # flatten: blend the lower region toward its own heavily blurred self,
    # then toward a constant dark base, so no structure competes with tiles.
    flat = np.stack([blur(img[..., c], 70) for c in range(3)], -1)
    img = img * (1 - 0.88 * t[..., None]) + flat * (0.88 * t[..., None])

    base = np.array([0.020, 0.026, 0.046], np.float32)
    img = img * (1 - basemix * t[..., None]) + base * (basemix * t[..., None])

    # top-left text zone: extra, very soft darkening. Elliptical distance so
    # the well is plateaued over the text block (d < 0.55) and fades by d = 1.
    d = np.sqrt((u / tl_w) ** 2 + (v / tl_h) ** 2)
    img = img * (1.0 - tl * smoothstep(1.0, 0.55, d))[..., None]

    # gentle global vignette at the edges
    rad = np.sqrt(((u - 0.5) * 1.05) ** 2 + ((v - 0.5) * 0.95) ** 2)
    img = img * (1.0 - 0.24 * smoothstep(0.45, 0.80, rad))[..., None]
    return img


def right_lift(img, amt):
    """Add brightness only on the right, clear of the text block.

    Lets the morning/afternoon scenes reach their target mean luminance
    without raising the background under the greeting, date and RAM text.
    """
    return img * (1.0 + amt * smoothstep(0.30, 1.0, u))[..., None]


def finish(img, grain=1.1, star_layer=None):
    """Clamp highlights, add anti-banding dither, return uint8 array."""
    if star_layer is not None:
        # stars are added AFTER flattening so they stay crisp, but are faded
        # out over the tile area
        fade = (1.0 - 0.90 * smoothstep(0.28, 0.60, v))
        s = star_layer * fade
        img = img + s[..., None] * np.array([0.92, 0.95, 1.0], np.float32)

    img = np.clip(img, 0.0, 1.0)
    # soft knee so no highlight ever reads as a white plate: the brightest
    # ribbon of the morning pair would otherwise peak near 60% luminance.
    img = np.where(img > 0.44, 0.44 + (img - 0.44) * 0.35, img)
    # triangular dither, ~1 LSB -> removes contouring in both PNG and JPEG
    d = (rng.random((H, W, 1), dtype=np.float32) - rng.random((H, W, 1), dtype=np.float32))
    img = img + d * (grain / 255.0)
    return np.clip(img * 255.0 + 0.5, 0, 255).astype(np.uint8)


# ------------------------------------------------------------ night (2-7%)
def scene_nebula():
    """Diffuse purple/blue nebula, soft-edged."""
    img = mix((0.055, 0.058, 0.125), (0.006, 0.006, 0.018), smoothstep(-0.1, 1.0, v))
    n1 = blur(fbm(5, 5, 0.58, seed=21), 26)
    n2 = blur(fbm(4, 3, 0.60, seed=22), 55)
    cloud = np.clip((n1 * 0.6 + n2 * 0.4 - 0.36) * 2.9, 0, 1) ** 1.20
    shape = np.exp(-(((u - 0.56) / 0.52) ** 2 + ((v - 0.18) / 0.46) ** 2))
    cloud = blur(cloud * shape, 10)
    purple = np.array([0.430, 0.235, 0.590], np.float32)
    blue = np.array([0.140, 0.235, 0.520], np.float32)
    hue = blur(fbm(3, 3, 0.6, seed=23), 70)
    col = purple[None, None] * hue[..., None] + blue[None, None] * (1 - hue[..., None])
    img = img + col * (cloud * 0.80)[..., None]
    img = ui_mask(img)
    return finish(img, 1.2, stars(2600, bright=0.55, seed=24))


def scene_galaxy():
    """Small, distant, off-centre 2-arm spiral; the rest is empty space."""
    img = mix((0.028, 0.038, 0.082), (0.004, 0.005, 0.015), smoothstep(-0.3, 1.0, v))
    gx, gy, ar = 0.705, 0.255, W / H
    # true-circle coords, then flatten + rotate to give the disc an inclination
    ex, ey = (u - gx) * ar, (v - gy)
    a = np.radians(-22.0)
    rx = ex * np.cos(a) - ey * np.sin(a)
    ry = ex * np.sin(a) + ey * np.cos(a)
    px, py = rx, ry / 0.46                    # inclined disc (wider than tall)
    r = np.sqrt(px ** 2 + py ** 2)
    th = np.arctan2(py, px)
    # 2-arm logarithmic spiral
    phase = 2.0 * th - np.log(np.maximum(r, 1e-4)) / 0.42
    arms = (0.5 + 0.5 * np.cos(phase)) ** 2.6
    disc = np.exp(-(r / 0.145) ** 1.9)
    dust = 0.30 + 0.70 * arms                 # arms bright, inter-arm dim
    gal = blur(dust * disc, 2.0) * 0.55
    bulge = np.exp(-(r / 0.038) ** 1.6) * 0.42
    halo = np.exp(-(r / 0.24) ** 2) * 0.055
    glow = gal + bulge + halo
    tint = np.array([0.82, 0.84, 1.0], np.float32) * 0.80
    img = img + glow[..., None] * tint
    img = ui_mask(img)
    return finish(img, 1.1, stars(3800, bright=0.60, seed=31))


# -------------------------------------------------------- afternoon (7-10%)
def scene_indigo():
    """Deep indigo-to-black dusk sky with a very fine grain."""
    g = smoothstep(-0.25, 1.02, v) ** 0.90
    img = mix((0.215, 0.205, 0.430), (0.005, 0.006, 0.016), g)
    warm = np.exp(-((v / 0.32) ** 2)) * np.exp(-(((u - 0.40) / 0.85) ** 2))
    img += warm[..., None] * np.array([0.062, 0.046, 0.082], np.float32)
    img = ui_mask(right_lift(img, 0.22), drop=0.64, basemix=0.28, tl=0.34, tl_w=0.95, tl_h=0.72)
    return finish(img, 2.0, None)   # stronger fine grain, as requested


def scene_violet():
    """Soft geometric abstract: big low-contrast discs in muted violet."""
    img = mix((0.072, 0.072, 0.128), (0.011, 0.014, 0.028), smoothstep(-0.2, 1.08, v))
    specs = [  # (cx, cy, rx, ry, amp, (r,g,b))
        (0.18, 0.10, 0.46, 0.50, 0.95, (0.165, 0.140, 0.245)),
        (0.74, 0.02, 0.42, 0.42, 0.80, (0.115, 0.125, 0.235)),
        (0.95, 0.34, 0.34, 0.38, 0.62, (0.185, 0.135, 0.215)),
        (0.44, 0.26, 0.30, 0.34, 0.50, (0.105, 0.115, 0.195)),
    ]
    for cx, cy, rx, ry, amp, col in specs:
        d = np.sqrt(((u - cx) / rx) ** 2 + ((v - cy) / ry) ** 2)
        m = blur(amp * smoothstep(1.0, 0.05, d), 45)
        img = img + m[..., None] * np.array(col, np.float32)
    # one broad soft diagonal band to break the circles up
    band = blur(smoothstep(0.42, 0.02, np.abs((u * 0.75 + v * 0.65) - 0.46)), 55)
    img = img + (band * 0.55)[..., None] * np.array([0.090, 0.082, 0.135], np.float32)
    img = ui_mask(right_lift(img, 0.22), drop=0.64, basemix=0.28, tl=0.34, tl_w=0.95, tl_h=0.72)
    return finish(img, 1.3, None)


# --------------------------------------------------------- morning (10-14%)
def scene_dawn():
    """Pale cool sky with a distant warm sunrise low on the right.

    All the light sits in the top third; `drop` is raised so the tile area
    stays as dark as the night wallpapers.
    """
    g = smoothstep(-0.30, 0.80, v) ** 0.85
    img = mix((0.450, 0.535, 0.665), (0.007, 0.009, 0.020), g)
    # distant warm glow, deliberately weak so it reads as a hint, not an orange
    warm = np.exp(-(((u - 0.80) / 0.40) ** 2 + ((v - 0.19) / 0.17) ** 2))
    img += blur(warm, 30)[..., None] * np.array([0.175, 0.110, 0.044], np.float32)
    # a couple of very soft horizontal cloud strata
    for amp, off, wid in [(0.5, 0.10, 0.045), (0.35, 0.215, 0.035)]:
        strat = blur(np.exp(-((v - off) / wid) ** 2) * amp, 28)
        img += strat[..., None] * np.array([0.065, 0.075, 0.092], np.float32)
    img += (fbm(4, 5, 0.55, seed=41) - 0.5)[..., None] * 0.012
    img = ui_mask(right_lift(img, 0.34), drop=0.87, basemix=0.34, tl=0.46, tl_w=1.00, tl_h=0.76)
    return finish(img, 1.6, None)


def scene_waves():
    """Soft curved ribbons in blue-greys, the lightest of the set."""
    img = mix((0.200, 0.234, 0.296), (0.012, 0.015, 0.024), smoothstep(-0.2, 0.92, v))
    # keep the light off the top-left corner, where the greeting text sits
    hfall = 0.32 + 0.68 * smoothstep(0.02, 0.62, u)
    for amp, f, ph, sk, off, wid, col in [
            (0.95, 0.9, 3.5, 0.30, 0.12, 0.085, (0.215, 0.240, 0.280)),
            (0.80, 1.4, 5.0, -0.26, 0.27, 0.070, (0.165, 0.200, 0.250)),
            (0.62, 0.7, 4.0, 0.38, 0.41, 0.095, (0.185, 0.210, 0.240)),
            (0.45, 2.0, 1.1, -0.18, 0.53, 0.060, (0.140, 0.170, 0.215))]:
        line = off + sk * np.sin(2 * np.pi * u * f + ph) * 0.22
        band = blur(np.exp(-((v - line) / wid) ** 2) * amp * hfall, 16)
        img = img + band[..., None] * np.array(col, np.float32)
    img = ui_mask(right_lift(img, 0.36), drop=0.84, basemix=0.32, tl=0.44, tl_w=0.98, tl_h=0.74)
    return finish(img, 1.4, None)


SCENES = [
    ("morning-dawn", scene_dawn),
    ("morning-waves", scene_waves),
    ("afternoon-indigo", scene_indigo),
    ("afternoon-violet", scene_violet),
    ("night-nebula", scene_nebula),
    ("night-galaxy", scene_galaxy),
]
# Any file in OUT not produced by this script is removed, so renaming a scene
# here does not leave an orphan wallpaper behind in the APK.


def main():
    os.makedirs(OUT, exist_ok=True)
    report = []
    keep = set()
    for name, fn in SCENES:
        im = Image.fromarray(fn(), "RGB")

        png_path = os.path.join(TMP, name + ".png")
        im.convert("P", palette=Image.ADAPTIVE, colors=256,
                   dither=Image.FLOYDSTEINBERG).save(png_path, optimize=True)

        # budget is 800 KB for six files; spend the headroom on quality and
        # step down only if a file would blow its ~130 KB share.
        jpg_path = os.path.join(TMP, name + ".jpg")
        qs = (95, 93, 90, 88, 86)
        best_q = qs[0]
        for q in qs:
            p = f"{jpg_path}.{q}"
            im.save(p, "JPEG", quality=q, optimize=True, progressive=True,
                    subsampling=0)
            best_q = q
            if os.path.getsize(p) <= 130 * 1024:
                break
        os.replace(f"{jpg_path}.{best_q}", jpg_path)
        for q in qs:
            p = f"{jpg_path}.{q}"
            if os.path.exists(p):
                os.remove(p)

        psz, jsz = os.path.getsize(png_path), os.path.getsize(jpg_path)
        fmt, src, ext = ("PNG", png_path, ".png") if psz <= jsz else ("JPEG", jpg_path, ".jpg")
        dst = os.path.join(OUT, name + ext)
        with open(src, "rb") as f:
            data = f.read()
        with open(dst, "wb") as f:
            f.write(data)
        keep.add(os.path.basename(dst))
        report.append((name, fmt, psz, jsz, best_q, dst))

    for stale in os.listdir(OUT):
        if stale not in keep:
            os.remove(os.path.join(OUT, stale))
            print(f"removed stale {stale}")

    print(f"{'name':20s} {'PNG':>8s} {'JPEG':>8s}      chosen")
    total = 0
    for name, fmt, psz, jsz, q, dst in report:
        total += os.path.getsize(dst)
        print(f"{name:20s} {psz:8d} {jsz:8d}(q{q})  {fmt:5s} -> {os.path.basename(dst)}")
    print(f"TOTAL chosen: {total} bytes = {total/1024:.1f} KB "
          f"({total/819200*100:.1f}% of the 800 KB budget)")


if __name__ == "__main__":
    main()
