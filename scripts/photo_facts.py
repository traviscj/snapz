#!/usr/bin/env python3
"""Image transforms that need no model: facts read straight off the file. Needs Pillow.

    geo        extract   where it was taken, from the photo's own GPS tag      -> meta `gps`, `place`
    quality    extract   is it sharp and bright enough to be worth keeping -> meta `quality`
    dedupe     extract   a perceptual hash, and which earlier photos in the collection look the same
                                                                               -> meta `phash`, `dup_of`

`geo` names a place only from a list you keep: nothing is sent to a geocoder. Put
[{"name": "office", "lat": 41.9, "lon": -87.6, "radius_m": 120}, ...] in ~/.config/snapz/places.json
(or the file SNAPZ_PLACES names); the nearest place whose radius covers the photo wins. Without the
file, photos still get their coordinates. Photos with no GPS tag are skipped: many arrive without
one (screenshots, web uploads from a phone, anything an app re-encoded).

    QUALITY_BLUR_BELOW   sharpness under this is called blurry   (default 40; see `--try`)
    DEDUPE_BITS          hashes differing in at most this many of 256 bits are the same picture (default 12)

    uv run --with pillow scripts/photo_facts.py [--follow]
    uv run --with pillow scripts/photo_facts.py --try FILE...     # print the numbers for local files
"""
import io
import json
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from snapz import Skip, _config, image_transform, run  # noqa: E402

_CFG = _config()


def _setting(name, default=None):
    return os.environ.get(name) or _CFG.get(name) or default


def _open(image):
    try:
        from PIL import Image
    except ImportError:
        raise SystemExit("photo_facts needs Pillow (uv run --with pillow …, or apt install python3-pil)") from None
    try:
        return Image.open(io.BytesIO(image))
    except Exception as e:
        raise Skip(f"can't read this image: {e}") from None


# ---- geo

def gps_of(image):
    """(lat, lon, altitude_m or None) from the EXIF GPS tag, or None."""
    gps = _open(image).getexif().get_ifd(0x8825)

    def degrees(v, ref):
        d, m, s = (float(x) for x in v)
        x = d + m / 60 + s / 3600
        return -x if str(ref).upper() in ("S", "W") else x
    try:
        lat, lon = degrees(gps[2], gps.get(1, "N")), degrees(gps[4], gps.get(3, "E"))
    except (KeyError, TypeError, ValueError, ZeroDivisionError):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180) or (lat == 0 and lon == 0):
        return None
    try:
        alt = float(gps[6]) * (-1 if gps.get(5) in (1, b"\x01") else 1)
    except (KeyError, TypeError, ValueError, ZeroDivisionError):
        alt = None
    return lat, lon, alt


def places():
    path = _setting("SNAPZ_PLACES") or os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "snapz", "places.json")
    try:
        with open(path) as fh:
            return [p for p in json.load(fh) if isinstance(p, dict) and "name" in p and "lat" in p and "lon" in p]
    except (OSError, ValueError):
        return []


def metres(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 6371008.8 * 2 * math.asin(math.sqrt(a))


def place_of(lat, lon, known=None):
    """(name, metres away) of the nearest known place whose radius covers the point, or (None, None)."""
    best = None
    for p in (places() if known is None else known):
        d = metres(lat, lon, float(p["lat"]), float(p["lon"]))
        if d <= float(p.get("radius_m", 100)) and (best is None or d < best[1]):
            best = (str(p["name"]), d)
    return best or (None, None)


@image_transform("geo", kind="extract",
                 description="Where the photo was taken, from its GPS tag; named from your own places list (no geocoder, no model). Sets meta.gps, meta.place.")
def geo_transform(intent, image, sz):
    at = gps_of(image)
    if at is None:
        raise Skip("no GPS tag in this photo")
    lat, lon, alt = at
    name, away = place_of(lat, lon)
    gps = {"lat": round(lat, 6), "lon": round(lon, 6)}
    if alt is not None:
        gps["alt_m"] = round(alt, 1)
    return {**gps, "place": name, "metres_from_place": None if away is None else round(away, 1)}, {"gps": gps, "place": name}


# ---- quality

def quality_of(image):
    """Sharpness (variance of the Laplacian on a 512px grayscale copy: scale-free enough to compare
    photos from one camera), mean brightness 0..255, and the share of pixels crushed or blown."""
    from PIL import ImageFilter, ImageOps, ImageStat
    im = ImageOps.exif_transpose(_open(image)).convert("L")
    im.thumbnail((512, 512))
    lap = im.filter(ImageFilter.Kernel((3, 3), [0, 1, 0, 1, -4, 1, 0, 1, 0], scale=1, offset=128))
    # The filter leaves a 1px border untouched; measure inside it.
    sharp = ImageStat.Stat(lap.crop((1, 1, lap.width - 1, lap.height - 1))).var[0]
    hist, n = im.histogram(), im.width * im.height
    mean = sum(i * c for i, c in enumerate(hist)) / n
    dark, blown = sum(hist[:8]) / n, sum(hist[248:]) / n
    flags = []
    if sharp < float(_setting("QUALITY_BLUR_BELOW", "40")):
        flags.append("blurry")
    if mean < 30 or dark > 0.6:
        flags.append("dark")
    # Blown highlights are reported as a number but not flagged: a screenshot or a sheet of paper is mostly white and fine.
    return {"sharp": round(sharp, 1), "mean": round(mean, 1), "dark": round(dark, 3), "blown": round(blown, 3), "flags": flags}


@image_transform("quality", kind="extract",
                 description="Sharpness and exposure; flags blurry / dark photos (no model). Sets meta.quality.")
def quality_transform(intent, image, sz):
    q = quality_of(image)
    return q, {"quality": {"sharp": q["sharp"], "mean": q["mean"], "flags": q["flags"]}}


# ---- dedupe

def dhash(image):
    """256-bit difference hash as 64 hex digits: survives resizing, recompression and small exposure changes.
    (64 bits is the usual size, but it cannot tell two mostly-white pages of text apart.)"""
    from PIL import Image, ImageOps
    im = ImageOps.exif_transpose(_open(image)).convert("L").resize((17, 16), Image.LANCZOS)
    px = im.tobytes()
    bits = 0
    for y in range(16):
        for x in range(16):
            bits = (bits << 1) | (px[y * 17 + x] > px[y * 17 + x + 1])
    return f"{bits:064x}"


def hamming(a, b):
    return bin(int(a, 16) ^ int(b, 16)).count("1")


_LISTING = {}   # (vault, collection) -> (when, [(token, phash, taken_at)]); a backfill asks once a minute, not once a photo


def _hashes(sz, vault, collection):
    key = (vault, collection)
    hit = _LISTING.get(key)
    if hit and time.monotonic() - hit[0] < 60:
        return hit[1]
    rows = [(p["token"], p["meta"]["phash"], p["taken_at"]) for p in sz.in_vault(vault).photos(collection) if (p.get("meta") or {}).get("phash")]
    _LISTING[key] = (time.monotonic(), rows)
    return rows


@image_transform("dedupe", kind="extract",
                 description="Perceptual hash; marks photos that look the same as an earlier one in the collection (no model). Sets meta.phash, meta.dup_of.")
def dedupe_transform(intent, image, sz):
    h = dhash(image)
    if not intent.get("photo") or not intent.get("collection"):
        return {"phash": h}, {"phash": h}
    me = sz.photo(intent["photo"])
    rows = _hashes(sz, me.get("vault_token") or me.get("vault"), intent["collection"])
    limit = int(_setting("DEDUPE_BITS", "12"))
    # Only earlier photos count, so of a pair exactly one is the duplicate and the first one stays clean.
    near = sorted((hamming(h, ph), tok) for tok, ph, taken in rows if tok != me["token"] and (taken, tok) < (me["taken_at"], me["token"]) and hamming(h, ph) <= limit)
    rows.append((me["token"], h, me["taken_at"]))
    dup = [tok for _, tok in near[:5]]
    return {"phash": h, "near": [{"photo": tok, "bits": d} for d, tok in near[:5]]}, {"phash": h, "dup_of": dup or None}


def _try(paths):
    seen = []
    for p in paths:
        with open(p, "rb") as fh:
            image = fh.read()
        try:
            at, q, h = gps_of(image), quality_of(image), dhash(image)
        except Skip as e:
            print(f"{p}: {e}")
            continue
        where = "no gps" if at is None else f"{at[0]:.6f},{at[1]:.6f} {place_of(at[0], at[1])[0] or '-'}"
        same = [f"{os.path.basename(o)}({hamming(h, oh)})" for o, oh in seen if hamming(h, oh) <= int(_setting("DEDUPE_BITS", "12"))]
        print(f"{p}: sharp {q['sharp']:>8} mean {q['mean']:>5} {','.join(q['flags']) or 'ok':<12} {h[:16]}… {where}{'  same as ' + ' '.join(same) if same else ''}")
        seen.append((p, h))


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--try":
        _try(sys.argv[2:])
    else:
        run()
