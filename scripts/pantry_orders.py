#!/usr/bin/env python3
"""Pantry UPC scans -> order items.

Every photo in the `pantry` collection that carries a barcode means "we need this". This turns the
photos feed into one record per UPC in the `order_items` namespace:

    order_items/<upc> = {"upc", "name", "brand", "size", "qty", "photos": [tokens], "last_scanned_at"}

`photos` is the set of live scans backing the item and `qty` its size, so the result is a pure
function of which scans currently exist: replaying the feed changes nothing, scanning the same
thing twice bumps qty, and deleting or moving a scan out of the pantry takes it back off the list
(the item disappears with its last scan). "Bought it" = delete the scans, or clear the item.

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
    wanted = photo["collection"] == "pantry" and not photo.get("deleted_at")
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


if __name__ == "__main__":
    run()
