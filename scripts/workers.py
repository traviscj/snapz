#!/usr/bin/env python3
"""Every image-transform worker in this directory in one process: the vision readers
(vision_transforms.py) and the model-free facts (photo_facts.py). What a service runs.

    uv run --with pillow scripts/workers.py --follow --poll 5
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import photo_facts  # noqa: E402,F401  (importing registers the transforms)
import vision_transforms  # noqa: E402,F401
from snapz import run  # noqa: E402

if __name__ == "__main__":
    run()
