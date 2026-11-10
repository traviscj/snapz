#!/usr/bin/env python3
"""Two external image transforms, to show the shape of a worker. macOS only (they shell out to
`sips`, which every Mac has), no Python dependencies.

    dominant-color   extract   average RGB of the photo -> result data + meta.dominant_color
    gray             rewrite   grayscale copy as a new version

Run it, then attach either to a collection (web UI → transforms) or queue one for a single photo
(photo detail → run):

    ./transform_examples.py            # catch up and exit
    ./transform_examples.py --follow   # keep working

Anything heavier — background removal, "how full is this bottle" — is the same three lines around
a model call: take the image bytes, return data or new bytes.
"""
import os
import subprocess
import tempfile

from snapz import Skip, image_transform, run


def _sips(image, *args, suffix=".jpg"):
    """Run sips over `image` bytes and return the output file's bytes."""
    with tempfile.TemporaryDirectory() as d:
        src, dst = os.path.join(d, "in"), os.path.join(d, "out" + suffix)
        with open(src, "wb") as fh:
            fh.write(image)
        r = subprocess.run(["sips", *args, src, "--out", dst], capture_output=True, text=True)
        if r.returncode != 0 or not os.path.exists(dst):
            raise Skip(f"sips could not read this image: {r.stderr.strip()[:120]}")
        with open(dst, "rb") as fh:
            return fh.read()


@image_transform("dominant-color", kind="extract", description="Average RGB of the photo (sips, external worker).")
def dominant_color(intent, image, sz):
    # Resample to a single pixel and read it straight out of the BMP: BGR at the pixel-data offset.
    bmp = _sips(image, "-s", "format", "bmp", "-z", "1", "1", suffix=".bmp")
    off = int.from_bytes(bmp[10:14], "little")
    b, g, r = bmp[off], bmp[off + 1], bmp[off + 2]
    hexcolor = f"#{r:02x}{g:02x}{b:02x}"
    return {"rgb": [r, g, b], "hex": hexcolor}, {"dominant_color": hexcolor}


@image_transform("gray", kind="rewrite", description="Grayscale copy (sips, external worker). Same geometry, so it becomes current.")
def gray(intent, image, sz):
    out = _sips(image, "-s", "format", "jpeg", "-m", "/System/Library/ColorSync/Profiles/Generic Gray Gamma 2.2 Profile.icc")
    return out, {"bytes_in": len(image), "bytes_out": len(out)}


if __name__ == "__main__":
    run()
