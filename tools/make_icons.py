"""Generate launcher icons for the VideoDownload app.

Produces legacy mipmap PNGs (mdpi..xxxhdpi) plus an adaptive-icon foreground
drawable (vector-like PNG with transparent padding) and the monochrome layer.
"""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = r"D:\zm\AIProj\DeepSeekJ\App\VideoDownload\app\src\main\res"
DPI = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def rounded_rect_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def gradient(size, top, bottom):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        t = y / max(1, size - 1)
        r = int(top[0] + (bottom[0] - top[0]) * t)
        g = int(top[1] + (bottom[1] - top[1]) * t)
        b = int(top[2] + (bottom[2] - top[2]) * t)
        for x in range(size):
            px[x, y] = (r, g, b)
    return img


def draw_glow(canvas, center, radius, color, alpha):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([center[0] - radius, center[1] - radius,
               center[0] + radius, center[1] + radius], fill=color + (alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(radius / 3))
    canvas.alpha_composite(layer)


def draw_mark(canvas, scale=1.0, color=(255, 255, 255, 255), shadow=True):
    """Play triangle inside a rounded 'download card' with a down arrow."""
    s = canvas.size[0]
    cx, cy = s / 2, s / 2

    # play triangle
    tri_w, tri_h = 0.30 * s * scale, 0.34 * s * scale
    tri = [
        (cx - tri_w * 0.42, cy - tri_h / 2),
        (cx - tri_w * 0.42, cy + tri_h / 2),
        (cx + tri_w * 0.62, cy),
    ]
    # download arrow (stem + head) below the triangle
    stem_w = 0.075 * s * scale
    stem_top = cy + tri_h * 0.62
    stem_bottom = cy + tri_h * 1.02
    head_w = 0.20 * s * scale
    head_top = stem_bottom - 0.01 * s
    head_bottom = stem_bottom + 0.14 * s * scale

    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.polygon(tri, fill=color)
    d.rounded_rectangle(
        [cx - stem_w / 2, stem_top, cx + stem_w / 2, stem_bottom],
        radius=stem_w / 2, fill=color)
    d.polygon([
        (cx - head_w / 2, head_top),
        (cx + head_w / 2, head_top),
        (cx, head_bottom),
    ], fill=color)

    if shadow:
        shadow_layer = layer.filter(ImageFilter.GaussianBlur(0.02 * s))
        shadow_tint = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        shadow_tint.putalpha(shadow_layer.getchannel("A").point(lambda a: int(a * 0.35)))
        canvas.alpha_composite(shadow_tint, (0, int(0.012 * s)))
    canvas.alpha_composite(layer)


def make_icon(size, round_icon=False):
    # Supersample for smooth edges
    ss = 4
    S = size * ss
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    bg = gradient(S, (84, 122, 255), (58, 62, 224)).convert("RGBA")
    draw_glow(canvas, (S * 0.25, S * 0.22), S * 0.42, (120, 200, 255), 90)
    canvas.paste(bg, (0, 0), rounded_rect_mask(S, S if round_icon else int(S * 0.24)))
    draw_glow(canvas, (S * 0.80, S * 0.85), S * 0.30, (255, 140, 200), 70)

    # keep the glow inside the icon silhouette
    silhouette = rounded_rect_mask(S, S if round_icon else int(S * 0.24))
    alpha = Image.composite(canvas.getchannel("A"), Image.new("L", (S, S), 0), silhouette)
    canvas.putalpha(alpha)

    draw_mark(canvas, scale=1.0)
    return canvas.resize((size, size), Image.LANCZOS)


def make_foreground(size):
    """Adaptive icon foreground: transparent with the mark in the safe zone."""
    ss = 4
    S = size * ss
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_mark(canvas, scale=0.72)
    return canvas.resize((size, size), Image.LANCZOS)


def main():
    # legacy square + round icons
    for dpi, px in DPI.items():
        out_dir = os.path.join(ROOT, f"mipmap-{dpi}")
        os.makedirs(out_dir, exist_ok=True)
        make_icon(px).save(os.path.join(out_dir, "ic_launcher.png"))
        make_icon(px, round_icon=True).save(os.path.join(out_dir, "ic_launcher_round.png"))

    # adaptive foreground / monochrome (108dp canvas, mdpi=108px)
    for dpi, px in DPI.items():
        out_dir = os.path.join(ROOT, f"mipmap-{dpi}")
        os.makedirs(out_dir, exist_ok=True)
        fg_px = int(px * 108 / 48)
        make_foreground(fg_px).save(os.path.join(out_dir, "ic_launcher_foreground.png"))

    # store icon for the settings screen / README
    os.makedirs(os.path.join(ROOT, "..", "store"), exist_ok=True)
    make_icon(512).save(os.path.join(ROOT, "..", "store", "icon-512.png"))
    print("icons written")


if __name__ == "__main__":
    main()
