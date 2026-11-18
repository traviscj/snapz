#!/usr/bin/env python3
"""Pantry UPC scans -> order items and stock.

A barcode says which item, not what happened to it. The verb is `meta.action`, picked when the
photo is filed into the pantry (inbox triage, or a sticky mode in the capture HUD when shooting
straight into it). This script owns that vocabulary: it registers the pantry's action set on
startup and is the only thing that gives the names meaning.

    (none) / out   "we need this"; `out` also means the shelf is empty     -> order_items, stock = 0
    consume        used `meta.qty` (default 1) of it                         -> stock -= qty
    purchase       bought qty                                                -> stock += qty
    count          counted the shelf                                         -> stock  = qty

Every photo in the `pantry` collection that carries a barcode and asks for more (no action, or
`out`) becomes one record per UPC in the `order_items` namespace:

    order_items/<upc> = {"upc", "name", "brand", "size", "qty", "photos": [tokens], "last_scanned_at"}

`photos` is the set of live scans backing the item and `qty` its size, so the result is a pure
function of which scans currently exist: replaying the feed changes nothing, scanning the same
thing twice bumps qty, and deleting or moving a scan out of the pantry takes it back off the list
(the item disappears with its last scan). "Bought it" = delete the scans, or clear the item.

Scans that carry an action are also folded, in taken_at order, into `stock`:

    stock/<upc> = {"upc", "qty", "known", "events": {photo token: [taken_at, action, qty]}, "last_action", "last_at"}

`known` is false until an `out` or `count` gives a baseline; before that `qty` is only the net of
what was scanned (it can be negative). Like order_items it is a function of the live scans:
deleting a mis-scan undoes it.

    ./pantry_orders.py            # catch up and exit (cron)
    ./pantry_orders.py --follow   # keep running
    ./pantry_orders.py --rewind   # rebuild from the start of the feed
"""
import json
import os
import urllib.request

from snapz import run, transform

LOOKUP = os.environ.get("SNAPZ_UPC_LOOKUP", "1") != "0"


def product(upc, sz):
    """Name/brand/size for a UPC via Open Food Facts, cached forever in the `products` namespace."""
    cached = sz.get_record("products", upc)
    if cached is not None:
        return cached
    info = {}
    if LOOKUP:
        try:
            url = f"https://world.openfoodfacts.org/api/v2/product/{upc}.json?fields=product_name,brands,quantity"
            req = urllib.request.Request(url, headers={"User-Agent": "snapz-pantry-orders/1.0"})
            with urllib.request.urlopen(req, timeout=8) as r:
                p = json.load(r).get("product") or {}
            info = {"name": p.get("product_name") or None, "brand": p.get("brands") or None, "size": p.get("quantity") or None}
        except Exception as e:  # lookup is best-effort; an unnamed item is still an order item
            print(f"lookup {upc} failed: {e}")
            return {}
        sz.put_record("products", upc, info)  # cache misses too ({}), so unknown UPCs aren't re-fetched
    return info


# No collection= filter on purpose: a scan moved OUT of the pantry must be seen to be retracted.
@transform("photos", consumer="pantry-orders", sink="order_items")
def order_items(photo, sz):
    # consume / purchase / count describe stock; only a bare scan or `out` asks for more.
    wanted = photo["collection"] == "pantry" and not photo.get("deleted_at") and photo["meta"].get("action") in (None, "out")
    for upc in photo["barcodes"]:
        item = sz.get_record("order_items", upc)
        if item is None and not wanted:
            continue
        item = item or {"upc": upc, "photos": []}
        scans = set(item["photos"])
        (scans.add if wanted else scans.discard)(photo["token"])
        if not scans:
            yield upc, None
            continue
        info = product(upc, sz)
        yield upc, {
            "upc": upc,
            "name": info.get("name"),
            "brand": info.get("brand"),
            "size": info.get("size"),
            "qty": len(scans),
            "photos": sorted(scans),
            "last_scanned_at": max(item.get("last_scanned_at") or "", photo["taken_at"]) if wanted else item.get("last_scanned_at"),
        }


ACTION_SET = [
    {"name": "consume", "label": "USE −", "qty": True},
    {"name": "out", "label": "OUT =0"},
    {"name": "purchase", "label": "BUY +", "qty": True},
    {"name": "count", "label": "COUNT =", "qty": True},
]
ACTIONS = tuple(a["name"] for a in ACTION_SET)


@transform("photos", consumer="pantry-stock", sink="stock")
def stock(photo, sz):
    meta = photo["meta"]
    action = meta.get("action")
    live = photo["collection"] == "pantry" and not photo.get("deleted_at") and action in ACTIONS
    try:
        n = max(0, int(meta.get("qty", 1)))
    except (TypeError, ValueError):
        n = 1
    for upc in photo["barcodes"]:
        rec = sz.get_record("stock", upc)
        if rec is None and not live:
            continue
        events = dict((rec or {}).get("events") or {})
        if live:
            events[photo["token"]] = [photo["taken_at"], action, n]
        else:
            events.pop(photo["token"], None)
        if not events:
            yield upc, None
            continue
        qty, known, last = 0, False, None
        for at, act, q in sorted(events.values()):
            if act == "purchase":
                qty += q
            elif act == "consume":
                qty = max(0, qty - q) if known else qty - q
            elif act == "out":
                qty, known = 0, True
            elif act == "count":
                qty, known = q, True
            last = (at, act)
        yield upc, {"upc": upc, "qty": qty, "known": known, "events": events, "last_action": last[1], "last_at": last[0]}


if __name__ == "__main__":
    import sys

    from snapz import Snapz, SnapzError
    if "--dry-run" not in sys.argv:
        try:
            Snapz().set_actions("pantry", ACTION_SET)
        except SnapzError as e:  # no pantry in this vault yet (404) or a read-only token (403): still worth running
            print(f"could not register the pantry action set: {e}", file=sys.stderr)
    run()
