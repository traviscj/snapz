#!/usr/bin/env python3
"""Two image transforms that ask a vision model, as an external worker.

    important-text   extract   the text worth finding again: license plates, serial / model / part
                               numbers, VINs, dates, lot codes, phone numbers, URLs, prices
                               -> result data {"items": [...]} + photo meta `text`
    fullness         extract   how full the container in the picture is, 0..1
                               -> result data {...} + photo meta `fullness` (and `fullness_of`)

The model is anything that speaks the OpenAI chat-completions API with image input: a local
llama.cpp / llama-swap / Ollama / vLLM server, or a hosted one. Nothing leaves your network unless
VISION_URL points outside it.

    VISION_URL      base URL ending in /v1          (default http://localhost:8080/v1)
    VISION_MODEL    model name to ask for           (required)
    VISION_API_KEY  bearer token, if the server wants one
    VISION_MAX_PX   longest side sent to the model  (default 1600; needs Pillow, else the photo goes as is)

Those, like SNAPZ_URL / SNAPZ_TOKEN, come from the environment or ~/.config/snapz/env.

    uv run --with pillow scripts/vision_transforms.py            # catch up and exit
    uv run --with pillow scripts/vision_transforms.py --follow   # keep working

Then attach either transform to a collection (web UI → transforms), or run one on a single photo
(photo detail → run). `--try FILE...` asks the model about local files and prints the answers,
without a snapz server: the quick way to see how a model does on your pictures.
"""
import base64
import io
import json
import os
import re
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from snapz import Skip, _config, image_transform, run  # noqa: E402

_CFG = _config()


def _setting(name, default=None):
    return os.environ.get(name) or _CFG.get(name) or default


KINDS = ["plate", "serial", "model", "part", "vin", "date", "lot", "phone", "url", "price", "name", "other"]

TEXT_PROMPT = """Read this photo and list the pieces of text that identify something, or that someone would want to find this photo by later:
license plates, serial numbers, model numbers, part numbers, VINs, dates (expiry, best-before, manufacture), lot or batch codes, phone numbers, URLs, prices, and names printed on labels or signs.
Leave out marketing copy, ingredients, instructions and other running text. Do not guess characters you cannot read; skip the item instead.

Answer with JSON only, in this shape:
{"items": [{"kind": "<one of: %s>", "text": "<exactly as printed>", "value": "<normalized: dates as YYYY-MM-DD (or YYYY-MM), plates and serials uppercase without spaces, otherwise same as text>", "label": "<the printed label beside it, like S/N, MODEL, EXP, or null>", "confidence": <0 to 1>}]}
Use {"items": []} when there is nothing of that sort.""" % ", ".join(KINDS)

FULLNESS_PROMPT = """Look at the main container in this photo (bottle, jug, jar, carton, can, glass, tank, bag).
Estimate how full it is: the fraction of its capacity that still holds contents, judged from the visible level. Account for the container's shape (a narrow neck holds little).
If the level cannot be seen or inferred (opaque container, closed box), say so with null rather than guessing.

Answer with JSON only, in this shape:
{"container": "<what it is>", "contents": "<what is in it, or null>", "fullness": <0 to 1, or null>, "confidence": <0 to 1>, "basis": "<a few words: what you judged it from>"}"""


def _prepare(image):
    """JPEG bytes no longer than VISION_MAX_PX on a side and the right way up. Without Pillow, the photo as it is."""
    try:
        from PIL import Image, ImageOps
    except ImportError:
        return image, "image/jpeg"
    try:
        im = ImageOps.exif_transpose(Image.open(io.BytesIO(image)))
    except Exception as e:
        raise Skip(f"can't read this image: {e}") from None
    im.thumbnail((int(_setting("VISION_MAX_PX", "1600")),) * 2)
    out = io.BytesIO()
    im.convert("RGB").save(out, "JPEG", quality=88)
    return out.getvalue(), "image/jpeg"


def _json_in(text):
    """The first JSON object in a model's reply (models like to wrap it in prose or a code fence)."""
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    start = text.find("{")
    while start != -1:
        depth, in_str, esc = 0, False, False
        for i in range(start, len(text)):
            ch = text[i]
            if in_str:
                esc = (ch == "\\") and not esc
                if ch == '"' and not esc:
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(text[start:i + 1])
                    except ValueError:
                        break
        start = text.find("{", start + 1)
    raise ValueError(f"no JSON in the model's reply: {text[:160]!r}")


def ask(image, prompt):
    """One question about one image -> the parsed JSON answer."""
    model = _setting("VISION_MODEL")
    if not model:
        raise SystemExit("VISION_MODEL is not set (env or ~/.config/snapz/env)")
    data, mime = _prepare(image)
    body = {
        "model": model,
        "temperature": 0,
        "max_tokens": 900,
        "messages": [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{base64.b64encode(data).decode()}"}},
            {"type": "text", "text": prompt},
        ]}],
    }
    headers = {"Content-Type": "application/json"}
    if _setting("VISION_API_KEY"):
        headers["Authorization"] = f"Bearer {_setting('VISION_API_KEY')}"
    req = urllib.request.Request(_setting("VISION_URL", "http://localhost:8080/v1").rstrip("/") + "/chat/completions",
                                 data=json.dumps(body).encode(), headers=headers, method="POST")
    try:
        # Generous: a local server may have to load the model first.
        with urllib.request.urlopen(req, timeout=float(_setting("VISION_TIMEOUT_S", "300"))) as r:
            reply = json.load(r)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:200]
        if e.code >= 500 or e.code == 429:
            raise RuntimeError(f"vision model: HTTP {e.code} {detail}") from None   # the worker retries, then records it
        raise Skip(f"vision model refused: HTTP {e.code} {detail}") from None
    except OSError as e:
        raise RuntimeError(f"vision model unreachable: {e}") from None
    return _json_in(reply["choices"][0]["message"]["content"] or "")


def _num(v):
    try:
        return max(0.0, min(1.0, float(v)))
    except (TypeError, ValueError):
        return None


def important_text(image):
    items = []
    for it in (ask(image, TEXT_PROMPT).get("items") or [])[:40]:
        if not isinstance(it, dict):
            continue
        text = str(it.get("text") or "").strip()
        if not text or len(text) > 200:
            continue
        kind = it.get("kind") if it.get("kind") in KINDS else "other"
        value = str(it.get("value") or text).strip()[:200]
        if kind in ("plate", "serial", "vin", "lot", "part", "model"):
            value = re.sub(r"\s+", "", value).upper() if kind in ("plate", "serial", "vin") else value.strip()
        label = it.get("label")
        items.append({"kind": kind, "text": text, "value": value, "label": str(label)[:40] if label else None, "confidence": _num(it.get("confidence"))})
    return items


def fullness_of(image):
    a = ask(image, FULLNESS_PROMPT)
    f = a.get("fullness")
    return {
        "container": (str(a["container"])[:80] if a.get("container") else None),
        "contents": (str(a["contents"])[:80] if a.get("contents") else None),
        "fullness": None if f is None else _num(f),
        "confidence": _num(a.get("confidence")),
        "basis": (str(a["basis"])[:160] if a.get("basis") else None),
    }


@image_transform("important-text", kind="extract",
                 description="Plates, serial / model / part numbers, dates, lot codes, phones, URLs, prices (vision model, external worker). Sets meta.text.")
def important_text_transform(intent, image, sz):
    items = important_text(image)
    # meta.text is what the photo carries around (and what search matches): kind + value, no model chatter.
    return {"items": items, "model": _setting("VISION_MODEL")}, {"text": [{"kind": i["kind"], "value": i["value"], "label": i["label"]} for i in items]}


@image_transform("fullness", kind="extract",
                 description="How full the container in the photo is, 0..1 (vision model, external worker). Sets meta.fullness.")
def fullness_transform(intent, image, sz):
    r = fullness_of(image)
    r["model"] = _setting("VISION_MODEL")
    if r["fullness"] is None:
        return r, {"fullness": None, "fullness_of": None}   # clears what an earlier run may have set
    return r, {"fullness": round(r["fullness"], 2), "fullness_of": r["contents"] or r["container"]}


def _try(paths):
    for p in paths:
        with open(p, "rb") as fh:
            image = fh.read()
        print(f"== {p}")
        try:
            for i in important_text(image):
                print(f"  {i['kind']:<7} {i['value']:<28} {('(' + i['label'] + ')') if i['label'] else '':<10} conf {i['confidence']}")
            f = fullness_of(image)
            pct = "?" if f["fullness"] is None else f"{round(f['fullness'] * 100)}%"
            print(f"  fullness {pct}  of {f['contents'] or f['container']}  conf {f['confidence']}  ({f['basis']})")
        except (Skip, RuntimeError, ValueError) as e:
            print(f"  failed: {e}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--try":
        _try(sys.argv[2:])
    else:
        run()
