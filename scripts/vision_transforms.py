#!/usr/bin/env python3
"""Image transforms that ask a vision model, as an external worker.

    look             extract   one cheap pass: a caption, tags, and what kinds of thing are in the photo;
                               then queues only the readers below that apply   -> meta `caption`, `tags`
    important-text   extract   the text worth finding again: license plates, serial / model / part
                               numbers, VINs, dates, lot codes, phone numbers, URLs, prices   -> meta `text`
    fullness         extract   how full the container in the picture is, 0..1  -> meta `fullness`, `fullness_of`
    address          extract   postal addresses and contact details (envelopes, signs, cards, trucks)
                                                                               -> meta `addresses`, `contacts`
    receipt          extract   merchant, date, totals, line items              -> meta `receipt`
    meter-read       extract   the number on a meter, gauge, odometer or scale -> meta `reading`
    nameplate        extract   an equipment rating plate, structured           -> meta `nameplate`
    expiry           extract   best-by / use-by / expiry date and the product  -> meta `expiry`
    whiteboard       extract   handwriting, whiteboards and notes as markdown  -> meta `transcript`
    check            extract   a paper check: who it is from (the name block, top left), plus the
                               number, date, amount and memo when they are in the shot   -> meta `check`

Put `look` in a collection's chain and each photo costs one model call plus one per reader that
applies, instead of one per reader. Any reader can also be attached or run on its own.

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

Then attach a transform to a collection (web UI → transforms), or run one on a single photo
(photo detail → run). `--try FILE...` asks the model about local files and prints the answers,
without a snapz server: the quick way to see how a model does on your pictures.
(`--try --only receipt,expiry FILE...` runs just those readers; default is `look` and what it picks.)
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

# What `look` can report seeing, and the reader each one calls for.
ROUTES = {
    "identifiers": "important-text",
    "address": "address",
    "contact": "address",
    "receipt": "receipt",
    "meter": "meter-read",
    "nameplate": "nameplate",
    "container": "fullness",
    "expiry": "expiry",
    "notes": "whiteboard",
    "check": "check",
}

LOOK_PROMPT = """Describe this photo for a personal photo index.

Answer with JSON only, in this shape:
{"caption": "<one plain sentence: what this is a photo of>", "tags": ["<3 to 8 lowercase search words: objects, materials, setting, brand>"], "has": ["<every one of the following that is clearly present, else leave it out>"]}

Values allowed in "has":
identifiers  a license plate, serial / model / part number, VIN, lot code, or a printed date
address      a postal street address (envelope, package label, sign, building number with street)
contact      a person's or business's phone, email, website or license number (business card, sign, vehicle lettering)
receipt      a store receipt, invoice or bill with amounts
meter        a utility meter, gauge, odometer, thermostat or scale showing a reading
nameplate    an equipment rating plate or spec label (appliance, HVAC, motor, electronics)
container    a bottle, jug, jar, carton, glass or tank whose fill level is visible
expiry       a best-by, use-by, sell-by or expiry date on a product
notes        a whiteboard, handwritten note, sticky note, to-do list or page of notes worth transcribing
check        a paper bank check (cheque) or money order, or the name-and-address corner of one"""

CHECK_PROMPT = """This is a photo of a paper bank check, or of just its top left corner. Read who the check is FROM.

The payer is the account holder: the name (or names, on a joint account) printed at the top left, usually with a postal address under it. It is printed, not handwritten. It is not the bank's name, not the "pay to the order of" line, and not the signature.
The rest of the check may be out of frame; anything you cannot see is null. The amount is the number in the box at the right (handwritten on a personal check): give it only if every digit is legible. Do not work it out from the amount written in words unless the box is unreadable and the words are clear.
Never copy the routing or account numbers printed along the bottom edge.

Answer with JSON only, in this shape:
{"payers": ["<each account-holder name exactly as printed, one per person or business>"], "street": "<payer's street line including any apt / suite, or null>", "city": null, "state": "<two letters, or null>", "zip": null, "number": "<check number, top right, digits only, or null>", "date": "<date written on the check as YYYY-MM-DD, or null>", "amount": <number or null>, "payee": "<pay to the order of, or null>", "memo": "<memo / for line, or null>", "bank": "<bank name, or null>", "confidence": <0 to 1 that the payer name is read correctly>}
If this is not a check, answer {"payers": []}."""

ADDRESS_PROMPT = """Read the postal addresses and contact details printed or written in this photo (envelope, package label, sign, business card, vehicle lettering, letterhead).
Do not guess characters you cannot read; leave that field null. Do not invent a city or ZIP that is not shown.

Answer with JSON only, in this shape:
{"addresses": [{"role": "<sender | recipient | location | business | other>", "name": "<who it is addressed to or belongs to, or null>", "street": "<number and street, or null>", "unit": "<apt / suite / floor, or null>", "city": null, "state": "<two letters if US, or null>", "zip": null, "country": null}],
 "contacts": [{"name": "<person, or null>", "company": null, "title": null, "phone": "<digits with country code if shown>", "email": null, "url": null, "license": "<license / registration number shown, or null>"}]}
Use empty lists when there is nothing of that sort."""

RECEIPT_PROMPT = """Read this receipt, invoice or bill.
Copy amounts exactly; do not compute or correct them. Leave anything you cannot read null.

Answer with JSON only, in this shape:
{"merchant": "<business name, or null>", "date": "<YYYY-MM-DD, or null>", "time": "<HH:MM 24h, or null>", "currency": "<ISO code such as USD, or null>", "subtotal": <number or null>, "tax": <number or null>, "tip": <number or null>, "total": <number or null>, "invoice": "<receipt / invoice / order number, or null>",
 "items": [{"desc": "<as printed>", "qty": <number or null>, "amount": <number or null>}]}
If this is not a receipt, invoice or bill, answer {"merchant": null, "total": null, "items": []}."""

METER_PROMPT = """Read the measuring device in this photo: a water, gas or electric meter, a gauge, an odometer, a thermostat or a scale.
Give the reading as a number, with the decimal point where the device marks one, and separately the digits exactly as the display shows them. For dial meters read each dial in order; when a pointer sits between two digits take the lower one.
If no reading can be read with confidence, use null rather than guessing.

Answer with JSON only, in this shape:
{"kind": "<water | gas | electric | odometer | pressure | temperature | scale | fuel | other>", "reading": <number or null>, "unit": "<unit shown or implied, such as gal, ft3, kWh, mi, psi, °F, lb, or null>", "digits": "<the digits exactly as shown, with leading zeros>", "meter_id": "<the meter's own serial or number if printed, or null>", "confidence": <0 to 1>}"""

NAMEPLATE_PROMPT = """Read the equipment rating plate or specification label in this photo.
Copy values exactly as printed. Leave anything not shown or not readable null; do not fill in from what such equipment usually has.

Answer with JSON only, in this shape:
{"type": "<what the equipment is: furnace, condenser, water heater, refrigerator, motor, router, ...>", "make": null, "model": null, "serial": null, "mfg_date": "<YYYY-MM or YYYY-MM-DD if printed, or null>",
 "specs": {"<printed label, lowercase, such as volts, amps, hz, phase, btu_in, btu_out, refrigerant, charge, capacity, watts, mca, mop, gallons, fcc_id>": "<value with its unit, as printed>"}}
If there is no rating plate or spec label, answer {"type": null, "make": null, "model": null, "serial": null, "specs": {}}."""

EXPIRY_PROMPT = """Find the date-coded shelf life on the product in this photo: best by, best before, use by, sell by, EXP, or a manufacture / packed-on date.
Read the date exactly; do not guess digits. Resolve two-digit years to 20YY. If only month and year are printed, give YYYY-MM.

Answer with JSON only, in this shape:
{"product": "<what the product is, with brand if visible, or null>", "date": "<YYYY-MM-DD or YYYY-MM, or null>", "kind": "<best_by | use_by | sell_by | exp | mfg | packed | unknown>", "as_printed": "<the date text exactly as printed>", "confidence": <0 to 1>}
Use "date": null when no such date is visible."""

WHITEBOARD_PROMPT = """Transcribe the whiteboard, note or list in this photo as Markdown.
Keep the structure that is there: headings, lists, checkboxes ("- [ ]" / "- [x]"), tables, arrows as "->". Keep the writer's words and spelling. Mark anything you cannot read as [?] rather than guessing.

Answer with JSON only, in this shape:
{"title": "<a few words for what this is, or null>", "markdown": "<the transcription>"}
Use "markdown": "" when there is no such writing to read."""


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


def ask(image, prompt, max_tokens=900):
    """One question about one image -> the parsed JSON answer."""
    model = _setting("VISION_MODEL")
    if not model:
        raise SystemExit("VISION_MODEL is not set (env or ~/.config/snapz/env)")
    data, mime = _prepare(image)
    body = {
        "model": model,
        "temperature": 0,
        "max_tokens": max_tokens,
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
    answer = _json_in(reply["choices"][0]["message"]["content"] or "")
    if not isinstance(answer, dict):
        raise ValueError("the model's reply was not a JSON object")
    return answer


# ---- cleaning what a model says into what a photo should carry

def _num(v):
    try:
        return max(0.0, min(1.0, float(v)))
    except (TypeError, ValueError):
        return None


def _s(v, n=120):
    """A trimmed string, or None for the many ways a model says "nothing"."""
    if v is None or isinstance(v, (dict, list)):
        return None
    s = str(v).strip()
    return None if not s or s.lower() in ("null", "none", "n/a", "unknown", "?") else s[:n]


def _amount(v):
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return round(float(v), 4)
    m = re.search(r"-?\d[\d,]*\.?\d*", str(v or ""))
    try:
        return round(float(m.group().replace(",", "")), 4) if m else None
    except ValueError:
        return None


def _date(v):
    s = _s(v, 10)
    return s if s and re.fullmatch(r"\d{4}-\d{2}(-\d{2})?", s) else None


def _rows(v, limit=20):
    return [x for x in (v if isinstance(v, list) else []) if isinstance(x, dict)][:limit]


def _compact(d):
    return {k: v for k, v in d.items() if v not in (None, "", [], {})}


# ---- the readers: image bytes -> cleaned answer

def look(image):
    a = ask(image, LOOK_PROMPT, max_tokens=400)
    tags = [t for t in (_s(t, 30) for t in (a.get("tags") if isinstance(a.get("tags"), list) else [])) if t]
    has = [h for h in (a.get("has") if isinstance(a.get("has"), list) else []) if h in ROUTES]
    return {"caption": _s(a.get("caption"), 200), "tags": [t.lower() for t in tags][:10], "has": list(dict.fromkeys(has))}


def important_text(image):
    items = []
    for it in _rows(ask(image, TEXT_PROMPT).get("items"), 40):
        text = str(it.get("text") or "").strip()
        if not text or len(text) > 200:
            continue
        kind = it.get("kind") if it.get("kind") in KINDS else "other"
        value = str(it.get("value") or text).strip()[:200]
        if kind in ("plate", "serial", "vin"):
            value = re.sub(r"\s+", "", value).upper()
        label = it.get("label")
        items.append({"kind": kind, "text": text, "value": value, "label": str(label)[:40] if label else None, "confidence": _num(it.get("confidence"))})
    return items


def fullness_of(image):
    a = ask(image, FULLNESS_PROMPT)
    f = a.get("fullness")
    return {
        "container": _s(a.get("container"), 80),
        "contents": _s(a.get("contents"), 80),
        "fullness": None if f is None else _num(f),
        "confidence": _num(a.get("confidence")),
        "basis": _s(a.get("basis"), 160),
    }


def addresses_in(image):
    a = ask(image, ADDRESS_PROMPT, max_tokens=1200)
    addresses, contacts = [], []
    for r in _rows(a.get("addresses"), 8):
        x = _compact({k: _s(r.get(k)) for k in ("role", "name", "street", "unit", "city", "state", "zip", "country")})
        if x.get("street") or (x.get("city") and x.get("zip")):
            # One line to read and to search by, as an envelope would have it.
            x["line"] = ", ".join(p for p in (" ".join(p for p in (x.get("street"), x.get("unit")) if p), x.get("city"),
                                              " ".join(p for p in (x.get("state"), x.get("zip")) if p)) if p)
            addresses.append(x)
    for r in _rows(a.get("contacts"), 8):
        x = _compact({k: _s(r.get(k)) for k in ("name", "company", "title", "phone", "email", "url", "license")})
        if any(x.get(k) for k in ("phone", "email", "url", "license")):
            contacts.append(x)
    return {"addresses": addresses, "contacts": contacts}


def receipt_in(image):
    a = ask(image, RECEIPT_PROMPT, max_tokens=2500)
    items = [_compact({"desc": _s(r.get("desc")), "qty": _amount(r.get("qty")), "amount": _amount(r.get("amount"))}) for r in _rows(a.get("items"), 80)]
    return {
        "merchant": _s(a.get("merchant")), "date": _date(a.get("date")), "time": _s(a.get("time"), 5), "currency": _s(a.get("currency"), 3),
        "subtotal": _amount(a.get("subtotal")), "tax": _amount(a.get("tax")), "tip": _amount(a.get("tip")), "total": _amount(a.get("total")),
        "invoice": _s(a.get("invoice"), 60), "items": [i for i in items if i.get("desc")],
    }


def meter_in(image):
    a = ask(image, METER_PROMPT, max_tokens=300)
    return {"kind": _s(a.get("kind"), 20), "reading": _amount(a.get("reading")), "unit": _s(a.get("unit"), 12), "digits": _s(a.get("digits"), 24),
            "meter_id": _s(a.get("meter_id"), 40), "confidence": _num(a.get("confidence"))}


def nameplate_in(image):
    a = ask(image, NAMEPLATE_PROMPT, max_tokens=1200)
    specs = a.get("specs") if isinstance(a.get("specs"), dict) else {}
    specs = {re.sub(r"[^a-z0-9]+", "_", str(k).lower()).strip("_")[:32]: _s(v, 60) for k, v in list(specs.items())[:30]}
    return {"type": _s(a.get("type"), 60), "make": _s(a.get("make"), 60), "model": _s(a.get("model"), 60), "serial": _s(a.get("serial"), 60),
            "mfg_date": _date(a.get("mfg_date")), "specs": {k: v for k, v in specs.items() if k and v}}


def expiry_in(image):
    a = ask(image, EXPIRY_PROMPT, max_tokens=300)
    return {"product": _s(a.get("product"), 80), "date": _date(a.get("date")), "kind": _s(a.get("kind"), 12), "as_printed": _s(a.get("as_printed"), 40),
            "confidence": _num(a.get("confidence"))}


def check_in(image):
    a = ask(image, CHECK_PROMPT, max_tokens=500)
    payers = [p for p in (_s(p, 80) for p in (a.get("payers") if isinstance(a.get("payers"), list) else [])) if p][:4]
    amount = _amount(a.get("amount"))
    number = re.sub(r"\D", "", _s(a.get("number"), 12) or "")
    street, city, state, zip_ = _s(a.get("street"), 80), _s(a.get("city"), 40), _s(a.get("state"), 2), _s(a.get("zip"), 10)
    return {
        "payers": payers, "payer": " / ".join(payers) or None,
        "address": ", ".join(p for p in (street, city, " ".join(p for p in (state, zip_) if p)) if p) or None,
        "number": number.lstrip("0") or None, "date": _date(a.get("date")),
        "amount": round(amount, 2) if amount and 0 < amount < 1_000_000 else None,
        "payee": _s(a.get("payee"), 80), "memo": _s(a.get("memo"), 120), "bank": _s(a.get("bank"), 60), "confidence": _num(a.get("confidence")),
    }


def whiteboard_in(image):
    a = ask(image, WHITEBOARD_PROMPT, max_tokens=3000)
    md = a.get("markdown")
    return {"title": _s(a.get("title"), 80), "markdown": md.strip()[:12000] if isinstance(md, str) else ""}


# ---- the transforms: what each reader leaves on the photo. A reader that finds nothing still
# answers ok and clears its keys, so a rerun after a retake does not leave the old answer behind.

def _out(data, patch):
    data["model"] = _setting("VISION_MODEL")
    return data, patch


@image_transform("look", kind="extract",
                 description="Caption + tags, then queues only the readers that apply (text, address, receipt, meter, nameplate, fullness, expiry, whiteboard, check). Sets meta.caption, meta.tags.")
def look_transform(intent, image, sz):
    # Filing a photo starts the destination's chain; if a chain already looked at it (say, in the inbox), once was enough.
    if intent.get("origin") == "chain" and intent.get("photo") and (sz.photo(intent["photo"]).get("meta") or {}).get("caption"):
        raise Skip("already looked at")
    r = look(image)
    queued = []
    # params {"route": false}: caption and tags only.
    if (intent.get("params") or {}).get("route") is not False and intent.get("photo"):
        for name in dict.fromkeys(ROUTES[h] for h in r["has"]):
            sz.request_transform(intent["photo"], name)
            queued.append(name)
    r["queued"] = queued
    return _out(r, {"caption": r["caption"], "tags": r["tags"] or None})


@image_transform("important-text", kind="extract",
                 description="Plates, serial / model / part numbers, dates, lot codes, phones, URLs, prices (vision model, external worker). Sets meta.text.")
def important_text_transform(intent, image, sz):
    items = important_text(image)
    # meta.text is what the photo carries around (and what search matches): kind + value, no model chatter.
    return _out({"items": items}, {"text": [{"kind": i["kind"], "value": i["value"], "label": i["label"]} for i in items]})


@image_transform("fullness", kind="extract",
                 description="How full the container in the photo is, 0..1 (vision model, external worker). Sets meta.fullness.")
def fullness_transform(intent, image, sz):
    r = fullness_of(image)
    if r["fullness"] is None:
        return _out(r, {"fullness": None, "fullness_of": None})
    return _out(r, {"fullness": round(r["fullness"], 2), "fullness_of": r["contents"] or r["container"]})


@image_transform("address", kind="extract",
                 description="Postal addresses and contact details: envelopes, labels, signs, business cards, truck lettering (vision model). Sets meta.addresses, meta.contacts.")
def address_transform(intent, image, sz):
    r = addresses_in(image)
    return _out(r, {"addresses": r["addresses"] or None, "contacts": r["contacts"] or None})


@image_transform("receipt", kind="extract",
                 description="Receipts, invoices, bills: merchant, date, subtotal / tax / total, line items (vision model). Sets meta.receipt.")
def receipt_transform(intent, image, sz):
    r = receipt_in(image)
    if r["total"] is None and not r["items"]:
        return _out(r, {"receipt": None})
    # Line items stay in the result; the photo carries the header and how many lines there were.
    return _out(r, {"receipt": _compact({**{k: r[k] for k in ("merchant", "date", "currency", "subtotal", "tax", "tip", "total", "invoice")}, "lines": len(r["items"])})})


@image_transform("meter-read", kind="extract",
                 description="The reading on a utility meter, gauge, odometer, thermostat or scale (vision model). Sets meta.reading.")
def meter_transform(intent, image, sz):
    r = meter_in(image)
    if r["reading"] is None:
        return _out(r, {"reading": None})
    return _out(r, {"reading": _compact({"value": r["reading"], "unit": r["unit"], "kind": r["kind"], "meter_id": r["meter_id"], "digits": r["digits"]})})


@image_transform("nameplate", kind="extract",
                 description="Equipment rating plates: type, make, model, serial, manufacture date, electrical / capacity specs (vision model). Sets meta.nameplate.")
def nameplate_transform(intent, image, sz):
    r = nameplate_in(image)
    if not (r["model"] or r["serial"]):
        return _out(r, {"nameplate": None})
    return _out(r, {"nameplate": _compact(r)})


@image_transform("expiry", kind="extract",
                 description="Best-by / use-by / expiry date and the product it is on (vision model). Sets meta.expiry.")
def expiry_transform(intent, image, sz):
    r = expiry_in(image)
    if r["date"] is None:
        return _out(r, {"expiry": None})
    return _out(r, {"expiry": _compact({"date": r["date"], "kind": r["kind"], "product": r["product"]})})


@image_transform("whiteboard", kind="extract",
                 description="Whiteboards, handwritten notes and lists, transcribed as Markdown (vision model). Sets meta.transcript.")
def whiteboard_transform(intent, image, sz):
    r = whiteboard_in(image)
    if not r["markdown"]:
        return _out(r, {"transcript": None, "transcript_title": None})
    return _out(r, {"transcript": r["markdown"][:4000], "transcript_title": r["title"]})


@image_transform("check", kind="extract",
                 description="A paper check, or just its top left corner: who it is from (name, address), and the number, date, amount and memo when they are in the shot (vision model). Never the routing / account line. Sets meta.check.")
def check_transform(intent, image, sz):
    r = check_in(image)
    if not r["payer"]:
        return _out(r, {"check": None})
    return _out(r, {"check": _compact({k: r[k] for k in ("payer", "address", "number", "date", "amount", "memo", "bank", "confidence")})})


READERS = {"important-text": important_text, "fullness": fullness_of, "address": addresses_in, "receipt": receipt_in,
           "meter-read": meter_in, "nameplate": nameplate_in, "expiry": expiry_in, "whiteboard": whiteboard_in, "check": check_in}


def _try(args):
    only = None
    if args[:1] == ["--only"]:
        only, args = [n for n in args[1].split(",") if n], args[2:]
    for p in args:
        with open(p, "rb") as fh:
            image = fh.read()
        print(f"== {p}")
        try:
            names = only
            if names is None:
                seen = look(image)
                print(f"  look           {json.dumps(seen, ensure_ascii=False)}")
                names = list(dict.fromkeys(ROUTES[h] for h in seen["has"]))
            for n in names:
                print(f"  {n:<14} {json.dumps(READERS[n](image), ensure_ascii=False)}")
        except (Skip, RuntimeError, ValueError) as e:
            print(f"  failed: {e}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--try":
        _try(sys.argv[2:])
    else:
        run()
