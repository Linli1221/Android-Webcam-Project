"""Checks the person-segmentation half of BackgroundRemover, the parts that can be verified
without an Android device:

  1. MaskWarp.prepareInput  - sensor frame -> upright 256x256 segmenter input
  2. MaskWarp.toSensorGrid  - upright mask -> sensor-aligned grid
  3. ChromaKeyProcessor.processMasked - threshold/feather remap and NV21 compositing

(1) and (2) are compared against numpy's own rotations, which is the only way to catch a rotation
that is inverted or mirrored - a round trip through the same wrong table would still pass. The
composite is compared against an ideal result built from the ground-truth silhouette.

Not shipped; mirrors AWA/.../processing/segmentation/*.kt and .../ChromaKeyProcessor.kt.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chroma_reference as C  # noqa: E402

BG_COLOR = 0x101018


# --------------------------------------------------------------------------- geometry

def prepare_input(rgb, rotation, size):
    """Mirror of MaskWarp.prepareInput: upright square, sampled from the sensor frame."""
    h, w = rgb.shape[:2]
    py, px = np.mgrid[0:size, 0:size].astype(np.float32)
    py = (py + 0.5) / size
    px = (px + 0.5) / size

    sx, sy = px.copy(), py.copy()
    if rotation == 90:
        sx, sy = py, 1.0 - px
    elif rotation == 270:
        sx, sy = 1.0 - py, px
    elif rotation == 180:
        sx, sy = 1.0 - px, 1.0 - py

    fx = np.clip(sx * w - 0.5, 0, w - 1)
    fy = np.clip(sy * h - 0.5, 0, h - 1)
    x0 = fx.astype(np.int32)
    y0 = fy.astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    wx = (fx - x0)[..., None]
    wy = (fy - y0)[..., None]

    src = rgb.astype(np.float32)
    top = src[y0, x0] * (1 - wx) + src[y0, x1] * wx
    bottom = src[y1, x0] * (1 - wx) + src[y1, x1] * wx
    return np.clip(top * (1 - wy) + bottom * wy, 0, 255).astype(np.uint8)


def to_sensor_grid(mask, rotation, size):
    """Mirror of MaskWarp.toSensorGrid: upright mask -> square grid in sensor orientation."""
    mh, mw = mask.shape
    sy_n, sx_n = np.mgrid[0:size, 0:size].astype(np.float32)
    sy = (sy_n + 0.5) / size
    sx = (sx_n + 0.5) / size

    px, py = sx.copy(), sy.copy()
    if rotation == 90:
        px, py = 1.0 - sy, sx
    elif rotation == 270:
        px, py = sy, 1.0 - sx
    elif rotation == 180:
        px, py = 1.0 - sx, 1.0 - sy

    fx = np.clip(px * mw - 0.5, 0, mw - 1)
    fy = np.clip(py * mh - 0.5, 0, mh - 1)
    x0 = fx.astype(np.int32)
    y0 = fy.astype(np.int32)
    x1 = np.minimum(x0 + 1, mw - 1)
    y1 = np.minimum(y0 + 1, mh - 1)
    wx = fx - x0
    wy = fy - y0

    src = mask.astype(np.float32)
    top = src[y0, x0] * (1 - wx) + src[y0, x1] * wx
    bottom = src[y1, x0] * (1 - wx) + src[y1, x1] * wx
    return np.clip(top * (1 - wy) + bottom * wy, 0, 255)


def check_rotation():
    rng = np.random.default_rng(7)
    size = 256
    # A square source means the sampling lands on pixel centres exactly, so the comparison is
    # against a pure rotation with no resampling noise.
    marker = np.zeros((size, size), np.uint8)
    marker[20:60, 100:150] = 255
    marker[150:170, 30:60] = 90
    src = np.stack([marker, marker[::-1], np.roll(marker, 40, axis=1)], axis=2)
    src = np.ascontiguousarray(src)

    print("rotation   prepare-input MAE   grid MAE   (0 = exact)")
    for rotation in (0, 90, 180, 270):
        # PIL/OpenCV convention: rotationDegrees is clockwise, so upright = rot90(sensor, -1)
        k = {0: 0, 90: -1, 180: 2, 270: 1}[rotation]
        expected = np.rot90(src, k) if rotation else src
        mine = prepare_input(src, rotation, size)
        mae_in = float(np.abs(mine.astype(np.float32) - expected.astype(np.float32)).mean())

        # and the mask has to go back the same way
        upright_mask = marker.astype(np.float32)
        back = to_sensor_grid(upright_mask, rotation, size)
        expected_back = np.rot90(upright_mask, -k) if rotation else upright_mask
        mae_grid = float(np.abs(back - expected_back).mean())

        print(f"{rotation:>8}   {mae_in:>17.3f}   {mae_grid:>8.3f}")

    del rng


# --------------------------------------------------------------------------- composite

def upsample_alpha(mask, threshold, feather, width, height):
    """Mirror of the upsampling inside processMasked (float instead of 8.8 fixed point)."""
    mh, mw = mask.shape
    x = np.arange(width, dtype=np.float32)
    fx = np.clip((x + 0.5) / width * mw - 0.5, 0, mw - 1)
    x0 = fx.astype(np.int32)
    x1 = np.minimum(x0 + 1, mw - 1)
    wx = fx - x0

    y = np.arange(height, dtype=np.float32)
    fy = np.clip((y + 0.5) / height * mh - 0.5, 0, mh - 1)
    y0 = fy.astype(np.int32)
    y1 = np.minimum(y0 + 1, mh - 1)
    wy = fy - y0

    top = mask[y0][:, x0] * (1 - wx) + mask[y0][:, x1] * wx
    bottom = mask[y1][:, x0] * (1 - wx) + mask[y1][:, x1] * wx
    m = top * (1 - wy)[:, None] + bottom * wy[:, None]

    value = m / 255.0
    return np.clip((value - threshold) / (2 * feather) + 0.5, 0.0, 1.0)


def composite(planes, alpha, background):
    y, u, v = planes
    by, bu, bv = C.solid_planes(BG_COLOR, alpha.shape[1], alpha.shape[0])
    y = y.astype(np.float32)
    u = u.astype(np.float32)
    v = v.astype(np.float32)
    by = by.astype(np.float32).reshape(y.shape)
    bu = bu.astype(np.float32).reshape(u.shape)
    bv = bv.astype(np.float32).reshape(v.shape)

    out_y = np.where(alpha <= 0, by, np.where(alpha >= 1, y, y * alpha + by * (1 - alpha)))

    # chroma uses the alpha of the 2x2 block it represents, exactly like the Kotlin loop
    ac = alpha[::2, ::2]
    out_u = u * ac + bu * (1 - ac)
    out_v = v * ac + bv * (1 - ac)
    del background
    return (
        np.clip(out_y, 0, 255).astype(np.uint8),
        np.clip(out_u, 0, 255).astype(np.uint8),
        np.clip(out_v, 0, 255).astype(np.uint8),
    )


def model_like_mask(truth_upright, size=256):
    """Stands in for the segmenter: soft, slightly noisy, low resolution, a bit off."""
    small = Image.fromarray((truth_upright * 255).astype(np.uint8)).resize(
        (size, size), Image.BILINEAR
    )
    small = small.filter(ImageFilter.GaussianBlur(2.5))
    arr = np.asarray(small).astype(np.float32)
    noise = np.random.default_rng(11).normal(0, 9, arr.shape)
    return np.clip(arr + noise, 0, 255)


def main():
    scene, subject = C.studio_scene()
    rgb = np.asarray(scene)
    height, width = rgb.shape[:2]
    rotation = 90  # what a phone in portrait reports for the back camera

    print(f"\nsensor frame {width}x{height}, rotationDegrees={rotation}")
    check_rotation()

    # --- ground truth in the upright frame, then a model-ish mask out of it ---
    k = {0: 0, 90: -1, 180: 2, 270: 1}[rotation]
    truth_upright = np.rot90(subject.astype(np.float32), k) if rotation else subject.astype(np.float32)
    mask = model_like_mask(truth_upright)

    # --- the real chain: upright mask -> sensor grid -> alpha -> composite ---
    grid = to_sensor_grid(mask, rotation, 256)

    # the geometry check: the sensor grid should look like the silhouette, only softer
    truth_small = np.asarray(
        Image.fromarray((subject * 255).astype(np.uint8)).resize((256, 256), Image.BILINEAR)
    ).astype(np.float32)
    print(f"\nsensor grid vs ground-truth silhouette: MAE {np.abs(grid - truth_small).mean():.1f} / 255")

    planes = C.rgb_to_nv21(rgb)
    ideal_alpha = subject.astype(np.float32)
    ideal = composite(planes, ideal_alpha, BG_COLOR)
    ideal_rgb = C.nv21_to_rgb(*ideal).astype(np.float32)

    print("\nfeather  threshold   MAE vs ideal   edge MAE   (255 = worst)")
    edge_band = np.asarray(
        Image.fromarray((subject * 255).astype(np.uint8))
        .filter(ImageFilter.MaxFilter(9))
    ) > 0
    edge_band &= ~(
        np.asarray(
            Image.fromarray((subject * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(9))
        ) > 0
    )

    results = []
    for feather, threshold in ((0.03, 0.5), (0.12, 0.5), (0.2, 0.5), (0.45, 0.5), (0.2, 0.62)):
        alpha = upsample_alpha(grid, threshold, feather, width, height)
        out = composite(planes, alpha, BG_COLOR)
        out_rgb = C.nv21_to_rgb(*out).astype(np.float32)
        mae = float(np.abs(out_rgb - ideal_rgb).mean())
        edge_mae = float(np.abs(out_rgb[edge_band] - ideal_rgb[edge_band]).mean())
        print(f"{feather:>7.2f}  {threshold:>9.2f}   {mae:>12.2f}   {edge_mae:>8.2f}")
        results.append((feather, threshold, alpha, out_rgb))

    # --- picture: default look, the difference to the ideal, and the edge at four feathers ---
    default = results[2]
    out_rgb = default[3]
    diff = np.abs(out_rgb - ideal_rgb).mean(axis=2)
    diff_vis = np.clip(diff * 4, 0, 255).astype(np.uint8)

    sheet = Image.new("RGB", (width * 2, height * 2), (10, 10, 12))
    sheet.paste(Image.fromarray(rgb), (0, 0))
    sheet.paste(Image.fromarray(np.clip(ideal_rgb, 0, 255).astype(np.uint8)), (width, 0))
    sheet.paste(Image.fromarray(np.clip(out_rgb, 0, 255).astype(np.uint8)), (0, height))
    sheet.paste(Image.fromarray(np.stack([diff_vis] * 3, axis=2)), (width, height))
    sheet.save("mask_composite.png")

    # zoomed edges: the same crop at four feathers
    cx, cy = int(width * 0.5), int(height * 0.3)
    half = 90
    crops = []
    for feather, threshold, _, out in results[:4]:
        im = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
        crops.append(im.crop((cx - half, cy - half, cx + half, cy + half)).resize((360, 360), Image.NEAREST))
    strip = Image.new("RGB", (360 * len(crops), 360), (10, 10, 12))
    for i, im in enumerate(crops):
        strip.paste(im, (i * 360, 0))
    strip.save("mask_edges.png")

    print("\nwrote mask_composite.png (original | ideal | pipeline | 4x difference)")
    print("wrote mask_edges.png (feather 0.03 | 0.12 | 0.2 | 0.45)")


if __name__ == "__main__":
    main()
