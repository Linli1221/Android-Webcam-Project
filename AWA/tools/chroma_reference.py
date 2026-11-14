"""Reference implementation of ChromaKeyProcessor (same maths as the Kotlin version).

Not shipped with the APK. It is here to keep the tunable constants honest: run it and you get the
alpha coverage, the auto-estimated key colour and a 2x2 before/after sheet (chroma_demo.png) for a
synthetic green screen with uneven lighting and green spill on the subject.

    python chroma_reference.py      # needs numpy + pillow
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 640, 480
MAX_CHROMA = 128.0


def rgb_to_nv21(rgb):
    r, g, b = rgb[..., 0].astype(np.float32), rgb[..., 1].astype(np.float32), rgb[..., 2].astype(np.float32)
    y = 0.299 * r + 0.587 * g + 0.114 * b
    u = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
    v = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
    y_plane = np.clip(y, 0, 255).astype(np.uint8)

    # 2x2 box subsampling, like a camera's YUV420 output
    uu = u.reshape(H // 2, 2, W // 2, 2).mean(axis=(1, 3))
    vv = v.reshape(H // 2, 2, W // 2, 2).mean(axis=(1, 3))
    u_plane = np.clip(uu, 0, 255).astype(np.uint8)
    v_plane = np.clip(vv, 0, 255).astype(np.uint8)
    return y_plane, u_plane, v_plane


def nv21_to_rgb(y_plane, u_plane, v_plane):
    yy = y_plane.astype(np.float32)
    uu = np.repeat(np.repeat(u_plane.astype(np.float32), 2, axis=0), 2, axis=1) - 128.0
    vv = np.repeat(np.repeat(v_plane.astype(np.float32), 2, axis=0), 2, axis=1) - 128.0
    r = yy + 1.402 * vv
    g = yy - 0.344136 * uu - 0.714136 * vv
    b = yy + 1.772 * uu
    return np.clip(np.stack([r, g, b], axis=-1), 0, 255).astype(np.uint8)


def key_chroma(color):
    r, g, b = (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF
    ku = int(np.clip(128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b, 0, 255))
    kv = int(np.clip(128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b, 0, 255))
    return ku, kv


def solid_planes(color, w, h):
    r, g, b = (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF
    yv = np.clip(0.299 * r + 0.587 * g + 0.114 * b, 0, 255)
    uv = np.clip(128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b, 0, 255)
    vv = np.clip(128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b, 0, 255)
    return (np.full((h, w), yv, np.uint8),
            np.full((h // 2, w // 2), uv, np.uint8),
            np.full((h // 2, w // 2), vv, np.uint8))


def estimate_key(u_plane, v_plane, blue=False):
    """Mirror of ChromaKeyProcessor.refineKey: mean chroma of the green-ish border ring."""
    h, w = u_plane.shape
    band_x, band_y = max(w // 14, 2), max(h // 14, 2)
    ring = np.zeros((h, w), bool)
    ring[:band_y, :] = True
    ring[-band_y:, :] = True
    ring[:, :band_x] = True
    ring[:, -band_x:] = True
    u = u_plane[ring].astype(np.int32)
    v = v_plane[ring].astype(np.int32)
    score = np.minimum(u - 128, 128 - v) if blue else np.minimum(128 - u, 128 - v)
    hit = score > 16
    if hit.sum() * 5 < u.size:
        return None
    return int(u[hit].mean()), int(v[hit].mean())


def process(y_plane, u_plane, v_plane, bg, key=0x00FF00, similarity=0.28, smoothness=0.12, spill=0.45,
            auto_key=True):
    ku, kv = key_chroma(key)
    if auto_key:
        est = estimate_key(u_plane, v_plane)
        if est is not None:
            ku, kv = est
    t = similarity * MAX_CHROMA
    fade = max(smoothness * MAX_CHROMA, 1.0)

    du = u_plane.astype(np.float32) - ku
    dv = v_plane.astype(np.float32) - kv
    d = np.sqrt(du * du + dv * dv)
    alpha = np.clip((d - t) / fade, 0.0, 1.0)

    # nearest-neighbour upsample to luma resolution, exactly like the Kotlin alphaRow
    a_full = np.repeat(np.repeat(alpha, 2, axis=0), 2, axis=1)
    u_full = np.repeat(np.repeat(u_plane.astype(np.float32), 2, axis=0), 2, axis=1)
    v_full = np.repeat(np.repeat(v_plane.astype(np.float32), 2, axis=0), 2, axis=1)

    yy = y_plane.astype(np.float32)
    fu = u_full - 128.0
    fv = v_full - 128.0
    r = yy + 1.402 * fv
    g = yy - 0.344136 * fu - 0.714136 * fv
    b = yy + 1.772 * fu

    cap = (r + b) * 0.5
    despill = (g > cap) & (a_full > 0)
    ng = np.where(despill, g - (g - cap) * spill, g)

    y2 = np.where(despill, 0.299 * r + 0.587 * ng + 0.114 * b, yy)
    u2 = np.where(despill, 128.0 - 0.168736 * r - 0.331264 * ng + 0.5 * b, u_full)
    v2 = np.where(despill, 128.0 + 0.5 * r - 0.418688 * ng - 0.081312 * b, v_full)

    bg_y = bg[0].astype(np.float32)
    out_y = np.where(a_full <= 0, bg_y, np.where(a_full >= 1, y2, y2 * a_full + bg_y * (1 - a_full)))
    out_y8 = np.clip(out_y, 0, 255).astype(np.uint8)

    # chroma is written once per 2x2 cell, taking the top-left pixel's value (as in the Kotlin pass)
    bg_u = bg[1].astype(np.float32)
    bg_v = bg[2].astype(np.float32)
    a_c = a_full[::2, ::2]
    u_c = u2[::2, ::2]
    v_c = v2[::2, ::2]
    out_u = u_c * a_c + bg_u * (1 - a_c)
    out_v = v_c * a_c + bg_v * (1 - a_c)
    return out_y8, np.clip(out_u, 0, 255).astype(np.uint8), np.clip(out_v, 0, 255).astype(np.uint8)


def studio_scene():
    """Synthetic close-up on an unevenly lit green screen, with green spill on the subject."""
    img = Image.new("RGB", (W, H))
    px = np.zeros((H, W, 3), np.float32)

    # green screen: uneven lighting + vignette, the usual real-world situation
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    light = 0.72 + 0.28 * (1.0 - ((xx - W * 0.5) ** 2 + (yy - H * 0.45) ** 2) / (W * H * 0.9))
    px[..., 0] = 46 * light
    px[..., 1] = 178 * light
    px[..., 2] = 62 * light

    img = Image.fromarray(np.clip(px, 0, 255).astype(np.uint8))

    # subject: shoulders, head, hair, shirt — drawn on a mask so spill can be added to the rim
    subject = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(subject)
    d.ellipse([W * 0.5 - 210, H * 0.86, W * 0.5 + 210, H * 1.30], fill=255)   # shoulders
    d.ellipse([W * 0.5 - 74, H * 0.14, W * 0.5 + 74, H * 0.62], fill=255)     # head
    d.ellipse([W * 0.5 - 96, H * 0.08, W * 0.5 + 96, H * 0.46], fill=255)     # hair
    d.ellipse([W * 0.5 - 62, H * 0.20, W * 0.5 + 62, H * 0.58], fill=255)     # face over hair
    subject = subject.filter(ImageFilter.GaussianBlur(1.2))

    body = Image.new("RGB", (W, H), (176, 52, 58))   # shirt
    db = ImageDraw.Draw(body)
    db.ellipse([W * 0.5 - 74, H * 0.14, W * 0.5 + 74, H * 0.62], fill=(228, 178, 146))  # face
    db.ellipse([W * 0.5 - 88, H * 0.08, W * 0.5 + 88, H * 0.40], fill=(58, 40, 32))     # hair
    db.ellipse([W * 0.5 - 60, H * 0.22, W * 0.5 + 60, H * 0.56], fill=(228, 178, 146))  # face again
    db.rectangle([W * 0.5 - 16, H * 0.50, W * 0.5 + 16, H * 0.62], fill=(214, 164, 134))  # neck

    out = Image.composite(body, img, subject)

    # green spill: the screen bounces onto the subject's edges
    arr = np.asarray(out).astype(np.float32)
    edge = np.asarray(subject.filter(ImageFilter.GaussianBlur(7))).astype(np.float32) / 255.0
    rim = np.clip(1.0 - np.abs(edge - 0.5) * 2.0, 0, 1) * (edge > 0.05)
    arr[..., 1] = np.clip(arr[..., 1] + 78 * rim, 0, 255)
    arr[..., 0] = np.clip(arr[..., 0] - 26 * rim, 0, 255)
    return Image.fromarray(arr.astype(np.uint8)), np.asarray(subject) > 128


def background_image():
    top = np.array([28, 34, 66], np.float32)
    bottom = np.array([120, 96, 150], np.float32)
    ramp = np.linspace(0, 1, H, dtype=np.float32)[:, None]
    px = top * (1 - ramp) + bottom * ramp
    px = np.repeat(px[:, None, :], W, axis=1)
    return Image.fromarray(px.astype(np.uint8))


def planes_from_image(im):
    rgb = np.asarray(im.convert("RGB"))
    return rgb_to_nv21(rgb)


def main():
    scene, subject = studio_scene()
    scene.save("scene.png")
    y, u, v = rgb_to_nv21(np.asarray(scene))

    # 1. solid colour background
    oy, ou, ov = process(y, u, v, solid_planes(0x101018, W, H))
    solid_out = Image.fromarray(nv21_to_rgb(oy, ou, ov))

    # 2. still-image background
    bg = planes_from_image(background_image())
    oy2, ou2, ov2 = process(y, u, v, bg)
    image_out = Image.fromarray(nv21_to_rgb(oy2, ou2, ov2))

    # 3. effect of the spill slider on a hard edge
    oy3, ou3, ov3 = process(y, u, v, solid_planes(0x101018, W, H), spill=0.0)
    no_spill = Image.fromarray(nv21_to_rgb(oy3, ou3, ov3))

    sheet = Image.new("RGB", (W * 2, H * 2), (12, 12, 12))
    sheet.paste(scene, (0, 0))
    sheet.paste(solid_out, (W, 0))
    sheet.paste(no_spill, (0, H))
    sheet.paste(image_out, (W, H))
    sheet.save("chroma_demo.png")

    est = estimate_key(u, v)
    seed = key_chroma(0x00FF00)
    print(f"key seed (pure green) = {seed}, auto estimate from frame = {est}")

    mask = Image.fromarray((subject * 255).astype(np.uint8))
    grown = np.asarray(mask.filter(ImageFilter.MaxFilter(41))) > 0
    rgb = np.asarray(scene).astype(np.float32)
    screen = (rgb[..., 1] > 90) & (rgb[..., 0] < 90)

    # the screen away from the subject must end up as the background colour (16, 16, 24)
    far = screen & ~grown
    keyed = np.asarray(solid_out).astype(np.float32)
    print("screen away from subject ->", np.round(keyed[far].mean(axis=0), 1), "(background is [16 16 24])")

    # the silhouette ring: how much green cast is left after keying, with and without despill
    band = (np.asarray(mask.filter(ImageFilter.MaxFilter(9))) > 0) & ~(
        np.asarray(mask.filter(ImageFilter.MinFilter(9))) > 0)

    def green_excess(a):
        return float(np.mean(a[..., 1][band] - (a[..., 0][band] + a[..., 2][band]) / 2))

    print(
        "green excess on the silhouette:",
        f"original {green_excess(rgb):.1f}",
        f"| despill off {green_excess(np.asarray(no_spill).astype(np.float32)):.1f}",
        f"| despill on {green_excess(keyed):.1f}",
    )

    # and the subject keeps its own colour
    shirt = (rgb[..., 0] > 140) & (rgb[..., 1] < 110)
    print("shirt kept:", np.round(np.asarray(image_out).astype(np.float32)[shirt].mean(axis=0), 1),
          "expected ~ [176 52 58]")


if __name__ == "__main__":
    main()
