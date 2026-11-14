"""Checks the Vulkan shaders against the CPU reference implementation.

`chroma_key_y.comp` / `chroma_key_uv.comp` are emulated instruction by instruction here - including
the 32-bit word packing and the NV21 byte addressing - and the result is compared with
`chroma_reference.process` on the same synthetic frame. Indexing or packing mistakes show up as a
large difference instead of as a picture that is subtly wrong on a phone.

Run: python AWA/tools/chroma_gpu_check.py
"""

import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chroma_reference as C

W, H = C.W, C.H
SIM, SMOOTH, SPILL = 0.28, 0.12, 0.45
MAX_DIFF = 3          # per byte; above this the emulation and the reference disagree


def clampf(x, lo, hi):
    return lo if x < lo else (hi if x > hi else x)


def pack(value):
    return int(clampf(value + 0.5, 0.0, 255.0))


def emulate(nv21, bg, key_cb, key_cr):
    """Mirror of the two compute shaders on a single thread."""
    sim = SIM * 128.0
    fade = max(SMOOTH * 128.0, 1.0)
    guard2 = ((sim + fade) * 2.0) ** 2
    uv_base = W * H
    cells_w, cells_h = W // 2, H // 2
    cell_count = cells_w * cells_h
    words_per_row = W // 4

    # ---- pass 1: luma, four bytes (one word) per invocation ----
    y_bytes = bytearray(W * H)
    for gy in range(H):
        for gx in range(words_per_row):
            word = 0
            for k in range(4):
                x = gx * 4 + k
                cell_byte = uv_base + (gy // 2) * W + (x & ~1)
                v = nv21[cell_byte]
                u = nv21[cell_byte + 1]
                du, dv = u - key_cb, v - key_cr
                d2 = du * du + dv * dv
                a = clampf((math.sqrt(d2) - sim) / fade, 0.0, 1.0)
                luma = float(nv21[gy * W + x])
                if SPILL > 0.0 and a > 0.0 and d2 < guard2:
                    fu, fv = u - 128.0, v - 128.0
                    r = luma + 1.402 * fv
                    g = luma - 0.344136 * fu - 0.714136 * fv
                    b = luma + 1.772 * fu
                    cap = (r + b) * 0.5
                    if g > cap:
                        ng = g - (g - cap) * SPILL
                        luma = 0.299 * r + 0.587 * ng + 0.114 * b
                bg_y = float(bg[gy * W + x])
                out = bg_y if a <= 0.0 else (luma if a >= 1.0 else luma * a + bg_y * (1.0 - a))
                word |= pack(out) << (k * 8)
            base = (gy * words_per_row + gx) * 4
            y_bytes[base:base + 4] = word.to_bytes(4, "little")

    # ---- pass 2: chroma, two cells (four bytes) per invocation ----
    uv_bytes = bytearray(W * H // 2)
    for cy in range(cells_h):
        for gx in range(words_per_row):
            word = 0
            for k in range(2):
                cx = gx * 2 + k
                cell_byte = uv_base + cy * W + cx * 2
                v = nv21[cell_byte]
                u = nv21[cell_byte + 1]
                du, dv = u - key_cb, v - key_cr
                d2 = du * du + dv * dv
                a = clampf((math.sqrt(d2) - sim) / fade, 0.0, 1.0)
                if SPILL > 0.0 and a > 0.0 and d2 < guard2:
                    luma = float(nv21[(cy * 2) * W + cx * 2])
                    fu, fv = u - 128.0, v - 128.0
                    r = luma + 1.402 * fv
                    g = luma - 0.344136 * fu - 0.714136 * fv
                    b = luma + 1.772 * fu
                    cap = (r + b) * 0.5
                    if g > cap:
                        ng = g - (g - cap) * SPILL
                        u = 128.0 - 0.168736 * r - 0.331264 * ng + 0.5 * b
                        v = 128.0 + 0.5 * r - 0.418688 * ng - 0.081312 * b
                cell = cy * cells_w + cx
                bg_v = float(bg[uv_base + cell])                  # background layout: Y | V | U
                bg_u = float(bg[uv_base + cell_count + cell])
                out_v = bg_v if a <= 0.0 else (v if a >= 1.0 else v * a + bg_v * (1.0 - a))
                out_u = bg_u if a <= 0.0 else (u if a >= 1.0 else u * a + bg_u * (1.0 - a))
                word |= (pack(out_v) | (pack(out_u) << 8)) << (k * 16)
            off = cy * W + gx * 4
            uv_bytes[off:off + 4] = word.to_bytes(4, "little")

    return bytes(y_bytes) + bytes(uv_bytes)


def to_nv21(y, u, v):
    out = bytearray(W * H * 3 // 2)
    out[:W * H] = y.tobytes()
    interleaved = np.empty((H // 2, W), dtype=np.uint8)
    interleaved[:, 0::2] = v
    interleaved[:, 1::2] = u
    out[W * H:] = interleaved.tobytes()
    return out


def split(nv21):
    y = np.frombuffer(nv21[:W * H], dtype=np.uint8).reshape(H, W)
    uv = np.frombuffer(nv21[W * H:], dtype=np.uint8).reshape(H // 2, W)
    return y, uv[:, 1::2].copy(), uv[:, 0::2].copy()


def main():
    scene, _subject = C.studio_scene()
    y, u, v = C.rgb_to_nv21(np.asarray(scene))

    bg_planes = C.solid_planes(0x101018, W, H)
    bg = bytearray(W * H * 3 // 2)
    bg[:W * H] = bg_planes[0].tobytes()
    bg[W * H:W * H + W * H // 4] = bg_planes[2].tobytes()
    bg[W * H + W * H // 4:] = bg_planes[1].tobytes()

    # Both sides get the same key colour: the one the border sampling produces for this frame.
    key_cb, key_cr = C.estimate_key(u, v)
    gpu = emulate(to_nv21(y, u, v), bg, key_cb, key_cr)

    oy, ou, ov = C.process(y, u, v, bg_planes, similarity=SIM, smoothness=SMOOTH,
                           spill=SPILL, auto_key=True)
    cpu = to_nv21(oy, ou, ov)

    diff = np.abs(np.frombuffer(gpu, np.uint8).astype(np.int16) -
                  np.frombuffer(cpu, np.uint8).astype(np.int16))
    psnr = 10 * math.log10(255.0 ** 2 / max((diff.astype(np.float64) ** 2).mean(), 1e-9))

    rgb_gpu = C.nv21_to_rgb(*split(gpu))
    rgb_cpu = C.nv21_to_rgb(*split(cpu))
    src = np.asarray(scene).astype(np.float32)
    screen = (src[..., 1] > 90) & (src[..., 0] < 90)

    print(f"key colour         : Cb={key_cb} Cr={key_cr}")
    print(f"max |gpu - cpu|    : {diff.max()}")
    print(f"mean |gpu - cpu|   : {diff.mean():.3f}")
    print(f"PSNR               : {psnr:.1f} dB")
    print(f"screen -> gpu      : {np.round(rgb_gpu[screen].mean(axis=0), 1)}")
    print(f"screen -> cpu      : {np.round(rgb_cpu[screen].mean(axis=0), 1)}  (background is [16 16 24])")

    if diff.max() > MAX_DIFF:
        print(f"\nFAILED: the shaders differ from the reference by more than {MAX_DIFF} levels")
        return 1
    print("\nOK - the shader maths match the CPU implementation")
    return 0


if __name__ == "__main__":
    sys.exit(main())
