"""Checks the background plate engine (PlateSubtractor) on synthetic scenes.

Nothing here needs a device: the point is to verify the three decisions that make or break a
difference keyer, each against a ground truth we generate ourselves.

  1. Exposure compensation - inject a known luma shift and check the median estimator recovers it,
     and that the matte survives the shift instead of flooding.
  2. Matte accuracy - IoU / precision / recall of the matte against the true silhouette, across the
     sensitivity slider, on a textured wall with a real shadow.
  3. Shadow rejection - the cast shadow must be background even though its luma differs a lot from
     the plate.
  4. Temporal smoothing - flicker (frame-to-frame change) on a static scene must drop, and the edge
     must still be soft rather than a one pixel step.

Mirrors PlateSubtractor.kt and ChromaKeyProcessor.processAlpha literally, constants included, so a
number that differs here differs on the phone.
"""
from pathlib import Path

from PIL import Image, ImageFilter

import numpy as np

# --------------------------------------------------------------------------- constants (mirror)

CAPTURE_SAMPLES = 3
LUMA_TOLERANCE_MAX = 46.0
LUMA_TOLERANCE_SPAN = 30.0
CHROMA_TOLERANCE_MAX = 40.0
CHROMA_TOLERANCE_SPAN = 28.0
EDGE_MIN = 0.1
EDGE_SCALE = 0.5
SHADOW_RANGE = 3.0
SHADOW_MIN_LUMA = 24.0
SHADOW_RATIO_MIN = 0.5
SHADOW_RATIO_MAX = 0.95
SHADOW_CHROMATICITY2 = 0.0036
OFFSET_STRIDE = 8
CHROMA_MATCH = 24
HIST_HALF = 63
MAX_OFFSET = 48


def rgb_to_yuv(rgb):
    r, g, b = (rgb[..., i] for i in range(3))  # 0..255, the range the coefficients expect
    y = 0.299 * r + 0.587 * g + 0.114 * b
    u = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
    v = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
    return y, np.clip(u, 0, 255), np.clip(v, 0, 255)


def chroma_planes(u, v):
    """NV21 subsampling: one Cb/Cr pair per 2x2 cell, taken from the top-left pixel."""
    return u[0::2, 0::2], v[0::2, 0::2]


def nv21(rgb):
    y, u, v = rgb_to_yuv(rgb)
    pu, pv = chroma_planes(u, v)
    return y, pu, pv


# --------------------------------------------------------------------------- scene

def make_scene(h=360, w=640, exposure=0.0, shift=0.0):
    """Textured wall + a person-shaped blob + a cast shadow.

    Returns plate_yuv, frame_yuv, silhouette (bool), so the matte can be scored honestly: the plate
    is the room, the frame is the room with somebody in it, and only the somebody changed.
    """
    rng = np.random.default_rng(7)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)

    # Wall: soft horizontal gradient plus texture (matte paint is not flat under a phone sensor).
    wall = np.zeros((h, w, 3), np.float32)
    base = 150 - 40 * (xx / w)
    wall[..., 0] = base + 4
    wall[..., 1] = base + 2
    wall[..., 2] = base - 2
    wall += rng.normal(0, 3.0, (h, w, 1))
    # Mottling at a few pixels: paint under a phone sensor is never flat, and this is the structure
    # that decides how much camera wobble the plate tolerates.
    wall += (np.sin(xx / 2.7) * np.sin(yy / 2.3) * 5.0)[..., None]

    # Something in the room that never moves: a poster with a strong colour, a table edge.
    wall[h - 90:h - 40, 60:230] = (92, 88, 120)
    wall[40:120, 470:590] = (200, 196, 182)

    person = np.zeros((h, w), bool)
    torso = ((xx - w * 0.52) / (w * 0.12)) ** 2 + ((yy - h * 0.78) / (h * 0.30)) ** 2 < 1.0
    head = (xx - w * 0.52) ** 2 / (w * 0.055) ** 2 + (yy - h * 0.34) ** 2 / (h * 0.10) ** 2 < 1.0
    person |= torso | head

    # Hair: a cap whose boundary is ragged at a few pixels, the way strands actually look. Per-pixel
    # dithering would be a strawman - no sensor produces that, and a 3x3 blur could not fix it anyway.
    dx = (xx - w * 0.52) / (w * 0.075)
    dy_ = (yy - h * 0.31) / (h * 0.13)
    angle = np.arctan2(dy_, dx)
    ragged = (0.10 * np.sin(9 * angle + 1.3) + 0.06 * np.sin(23 * angle) +
              0.03 * np.sin(41 * angle + 0.7))
    hair = np.sqrt(dx * dx + dy_ * dy_) < 1.0 + ragged
    person |= hair

    subject = np.zeros((h, w, 3), np.float32)
    subject[torso] = (58, 62, 84)        # dark trousers and jacket
    subject[head] = (196, 152, 128)      # skin
    subject[hair] = (44, 34, 30)         # hair

    frame_rgb = wall.copy()
    frame_rgb[person] = subject[person]

    # A white shirt panel: bright, close to the wall in luma, off in chroma. This is what the chroma
    # axis is for - a luma-only keyer sees nothing here.
    shirt = person & (yy > h * 0.72) & (np.abs(xx - w * 0.52) < w * 0.07)
    frame_rgb[shirt] = (214, 210, 205)

    # Cast shadow: the wall, 55 levels darker, softened edge. Must come out as background.
    shadow = (xx - w * 0.68) ** 2 / (w * 0.13) ** 2 + (yy - h * 0.90) ** 2 / (h * 0.16) ** 2 < 1.0
    shadow &= ~person
    frame_rgb[shadow] = wall[shadow] * 0.72

    # Exposure drift: the phone's AE drops the whole scene when the subject walks in.
    plate_rgb = wall + shift
    frame_rgb = frame_rgb * (1.0 - exposure)
    return (nv21(plate_rgb), nv21(frame_rgb), person, shadow)


# --------------------------------------------------------------------------- engine

def estimate_offset(y, plate_y, u, v, plate_u, plate_v):
    du = u - plate_u
    dv = v - plate_v
    match = du * du + dv * dv < CHROMA_MATCH ** 2
    h, w = y.shape
    match_full = np.repeat(np.repeat(match, 2, 0), 2, 1)[:h, :w]
    d = y - plate_y
    sampled = d[::OFFSET_STRIDE, ::OFFSET_STRIDE][match_full[::OFFSET_STRIDE, ::OFFSET_STRIDE]]
    if sampled.size < 400:
        return 0
    hist = np.clip(sampled, -HIST_HALF, HIST_HALF).astype(int) + HIST_HALF
    counts = np.bincount(hist, minlength=HIST_HALF * 2 + 1)
    median = int(np.searchsorted(np.cumsum(counts), sampled.size / 2) - HIST_HALF)
    return int(np.clip(median, -MAX_OFFSET, MAX_OFFSET))


def matte(y, plate_y, u, v, plate_u, plate_v, offset, sensitivity, feather_slider):
    luma_tol = LUMA_TOLERANCE_MAX - LUMA_TOLERANCE_SPAN * sensitivity
    chroma_tol = CHROMA_TOLERANCE_MAX - CHROMA_TOLERANCE_SPAN * sensitivity
    feather = EDGE_MIN + feather_slider * EDGE_SCALE
    shadow_limit = luma_tol * SHADOW_RANGE

    dy = y - (plate_y + offset)
    du = np.repeat(np.repeat(u - plate_u, 2, 0), 2, 1)[: y.shape[0], : y.shape[1]]
    dv = np.repeat(np.repeat(v - plate_v, 2, 0), 2, 1)[: y.shape[0], : y.shape[1]]

    chroma_score = np.sqrt(du * du + dv * dv) / chroma_tol
    score = np.maximum(np.abs(dy) / luma_tol, chroma_score)

    # Shadow test on chromaticity, which a light scaling does not change but a dark object does.
    ref_y = plate_y + offset
    cur_u = np.repeat(np.repeat(u, 2, 0), 2, 1)[: y.shape[0], : y.shape[1]]
    cur_v = np.repeat(np.repeat(v, 2, 0), 2, 1)[: y.shape[0], : y.shape[1]]
    pl_u = np.repeat(np.repeat(plate_u, 2, 0), 2, 1)[: y.shape[0], : y.shape[1]]
    pl_v = np.repeat(np.repeat(plate_v, 2, 0), 2, 1)[: y.shape[0], : y.shape[1]]
    with np.errstate(divide="ignore", invalid="ignore"):
        dc_u = (cur_u - 128) / y - (pl_u - 128) / ref_y
        dc_v = (cur_v - 128) / y - (pl_v - 128) / ref_y
    ratio = y / np.maximum(ref_y, 1e-3)
    shadow = (dy < 0) & (-dy < shadow_limit) & (y >= SHADOW_MIN_LUMA) & (ref_y >= SHADOW_MIN_LUMA)
    shadow &= (ratio >= SHADOW_RATIO_MIN) & (ratio <= SHADOW_RATIO_MAX)
    shadow &= (dc_u ** 2 + dc_v ** 2) < SHADOW_CHROMATICITY2
    score = np.where(shadow, 0.0, score)

    alpha = np.clip((score - 1.0) / feather, 0, 1) * 255.0
    return box_blur(alpha).round()


def box_blur(a):
    """3x3 box blur with clamped edges, as in blurHorizontal + blurVertical."""
    p = np.pad(a, 1, mode="edge")
    return sum(p[dy:dy + a.shape[0], dx:dx + a.shape[1]] for dy in range(3) for dx in range(3)) / 9.0


def iou(alpha, truth, threshold=128):
    got = alpha >= threshold
    inter = np.count_nonzero(got & truth)
    union = np.count_nonzero(got | truth)
    return inter / union if union else 1.0


def precision_recall(alpha, truth, threshold=128):
    got = alpha >= threshold
    tp = np.count_nonzero(got & truth)
    fp = np.count_nonzero(got & ~truth)
    fn = np.count_nonzero(~got & truth)
    p = tp / (tp + fp) if tp + fp else 1.0
    r = tp / (tp + fn) if tp + fn else 1.0
    return p, r


# --------------------------------------------------------------------------- checks

def check_offset():
    print("1. exposure compensation")
    ok = True
    for exposure, shift in ((0.0, 0.0), (0.10, 0.0), (0.18, 0.0), (0.0, 12.0)):
        (py, pu, pv), (y, u, v), person, _ = make_scene(exposure=exposure, shift=shift)
        # The plate is the wall; the frame is the wall through the AE change. The true difference is
        # exposure * (wall - black), which is not constant, so compare on the median instead.
        expected = int(np.median(y[::4, ::4] - py[::4, ::4]))
        got = estimate_offset(y, py, u, v, pu, pv)
        err = abs(got - int(np.clip(expected, -MAX_OFFSET, MAX_OFFSET)))
        ok &= err <= 6
        print(f"   exposure={exposure:.2f} shift={shift:+.0f}  median={expected:+4d}  "
              f"estimate={got:+4d}  error={err}")
    return ok


def check_matte():
    print("\n2. matte accuracy (textured wall, hair, white shirt, cast shadow)")
    ok = True
    (py, pu, pv), (y, u, v), person, shadow = make_scene()
    offset = estimate_offset(y, py, u, v, pu, pv)
    # Interior = silhouette eroded by 5 px, so the score ignores the boundary band on purpose.
    interior = np.array(Image.fromarray((person * 255).astype(np.uint8))
                        .filter(ImageFilter.MinFilter(11))) > 127
    for sensitivity in (0.2, 0.35, 0.5, 0.65, 0.8):
        for feather in (0.0, 0.8):
            a = matte(y, py, u, v, pu, pv, offset, sensitivity, feather)
            p, r = precision_recall(a, person)
            inside = a[interior].mean()
            # The edge ramp must not reach into the subject: a partially transparent torso lets the
            # replacement background show through, which is the classic "cheap matte" look.
            ok &= inside > 250
            print(f"   sensitivity={sensitivity:.2f} feather={feather:.1f}  IoU={iou(a, person):.3f}  "
                  f"precision={p:.3f}  recall={r:.3f}  interior alpha={inside:5.1f}  "
                  f"wall kept={np.mean(a[~person & ~shadow]) / 255 * 100:4.1f}%")
    return ok


def check_shadow():
    print("\n3. shadow rejection")
    (py, pu, pv), (y, u, v), person, shadow = make_scene()
    offset = estimate_offset(y, py, u, v, pu, pv)
    a = matte(y, py, u, v, pu, pv, offset, 0.5, 0.35)
    leaked = np.mean(a[shadow]) / 255.0
    print(f"   mean matte over the cast shadow: {leaked * 100:.1f}% (target < 25%)")
    print(f"   mean matte over untouched wall:  {np.mean(a[~person & ~shadow]) / 255.0 * 100:.1f}%")
    return leaked < 0.25


def check_temporal():
    print("\n4. temporal smoothing and edge softness")
    (py, pu, pv), (y, u, v), person, _ = make_scene()
    offset = estimate_offset(y, py, u, v, pu, pv)
    base = matte(y, py, u, v, pu, pv, offset, 0.5, 0.35)

    rng = np.random.default_rng(3)
    flicker_raw, flicker_smooth = [], []
    prev_raw = prev_smooth = None
    smooth = None
    keep = int((1 - 0.4) * 256)
    for _ in range(12):
        noisy = y + rng.normal(0, 2.2, y.shape)
        a = matte(noisy, py, u, v, pu, pv, offset, 0.5, 0.35)
        smooth = a.copy() if smooth is None else smooth + (((a - smooth).astype(int) * keep) >> 8)
        if prev_raw is not None:
            flicker_raw.append(np.mean(np.abs(a - prev_raw)))
            flicker_smooth.append(np.mean(np.abs(smooth - prev_smooth)))
        prev_raw, prev_smooth = a, smooth
    print(f"   frame-to-frame change: raw={np.mean(flicker_raw):.2f}/255  "
          f"stabilised={np.mean(flicker_smooth):.2f}/255")
    ratio = np.mean(flicker_smooth) / max(np.mean(flicker_raw), 1e-6)

    edge = np.count_nonzero((base > 8) & (base < 247)) / max(np.count_nonzero(base >= 8), 1)
    print(f"   matte pixels that are partially transparent: {edge * 100:.1f}% (a hard cut would be ~0%)")
    return ratio < 0.75 and edge > 0.02


def check_composite():
    print("\n5. composite (processAlpha: per-cell max alpha for chroma)")
    (py, pu, pv), (y, u, v), person, _ = make_scene()
    offset = estimate_offset(y, py, u, v, pu, pv)
    a = matte(y, py, u, v, pu, pv, offset, 0.5, 0.35) / 255.0

    bg = np.zeros_like(y), np.full_like(pu, 128.0), np.full_like(pv, 128.0)
    out_y = a * y + (1 - a) * bg[0]

    # Chroma from the 2x2 cell of the largest alpha, which is what keeps a rim of background colour
    # from bleeding into the silhouette.
    cells = np.maximum.reduce([
        a[0::2, 0::2], a[0::2, 1::2], a[1::2, 0::2], a[1::2, 1::2]
    ])
    du = u
    out_u = cells * du + (1 - cells) * 128.0

    solid = person & (a > 0.99)
    err_y = np.abs(out_y[solid] - y[solid]).mean() if solid.any() else 0
    print(f"   opaque foreground luma error: {err_y:.2f}/255")
    print(f"   chroma of fully covered cells preserved: "
          f"{np.mean(np.abs(out_u[cells > 0.99] - du[cells > 0.99])):.2f}/255")
    return err_y < 1.0


def check_hard_cases():
    """The two situations a difference keyer cannot fully resolve, measured rather than assumed.

    A dark object with the wall's exact hue looks exactly like a shadow once the light is scaled.
    The brightness ratio is what decides, so the honest statement is a range, not a promise.
    """
    print("\n6. hard cases (same hue as the wall)")
    h, w = 240, 400
    rng = np.random.default_rng(11)
    wall = np.zeros((h, w, 3), np.float32) + np.array([150, 152, 146], np.float32)
    wall += rng.normal(0, 1.2, (h, w, 1))
    plate = nv21(wall)

    def run(ratio, tint=(0, 0, 0)):
        frame = wall.copy()
        patch = np.zeros((h, w), bool)
        patch[60:180, 60:180] = True
        frame[patch] = wall[patch] * ratio + np.array(tint, np.float32)
        y, u, v = nv21(frame)
        a = matte(y, plate[0], u, v, plate[1], plate[2], 0, 0.5, 0.35)
        return np.mean(a[patch])

    dark_cloth = run(0.42)                 # a dark grey hoodie: must stay foreground
    shadow = run(0.72)                     # a cast shadow on the same wall: must go
    tinted = run(0.72, tint=(0, 0, 6))     # shadow with a slight colour cast - still a shadow
    print(f"   neutral grey at 42% brightness: matte={dark_cloth:5.1f} (kept as foreground)")
    print(f"   neutral grey at 72% brightness: matte={shadow:5.1f} (read as shadow, removed)")
    print(f"   shadow with a 6-level blue cast: matte={tinted:5.1f}")
    print("   -> an object at 50-95% of the wall brightness with the same hue is indistinguishable")
    print("      from a shadow; below 50% it is always kept, which is the safer half of the trade.")
    return dark_cloth > 200 and shadow < 30 and tinted < 60


def check_shift():
    """How much camera wobble the plate survives, measured on the same textured wall."""
    print("\n7. camera wobble (wall shifted by n pixels, subject in place)")
    ok = True
    for shift in (1, 2, 4):
        (py, pu, pv), (y, u, v), person, shadow = make_scene()
        wall_shift = np.roll(y, shift, axis=1)
        y2 = np.where(person, y, wall_shift)
        person_c = person[0::2, 0::2]
        u2 = np.where(person_c, u, np.roll(u, shift // 2, axis=1))
        v2 = np.where(person_c, v, np.roll(v, shift // 2, axis=1))
        off = estimate_offset(y2, py, u2, v2, pu, pv)
        a = matte(y2, py, u2, v2, pu, pv, off, 0.5, 0.35)
        leak = np.mean(a[~person & ~shadow]) / 255.0
        kept = np.mean(a[person] > 128)
        print(f"   {shift} px: wall wrongly kept={leak * 100:5.1f}%   subject kept={kept * 100:5.1f}%")
        ok &= leak < 0.35 * shift
    return ok


def main():
    results = {
        "exposure": check_offset(),
        "matte": check_matte(),
        "shadow": check_shadow(),
        "temporal": check_temporal(),
        "composite": check_composite(),
        "hard": check_hard_cases(),
        "shift": check_shift(),
    }
    print("\n" + ("ALL CHECKS PASSED" if all(results.values()) else "FAILED: " +
                  ", ".join(k for k, v in results.items() if not v)))
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
