#!/usr/bin/env python3
"""snapz client + feed consumer / transform library. Stdlib only; copy this one file anywhere.

    export SNAPZ_URL=https://snapz.example.net  SNAPZ_TOKEN=snapz_...
    (or put those two KEY=VALUE lines in ~/.config/snapz/env, chmod 600; the environment wins)

Library:

    from snapz import Snapz, transform, run

    @transform("photos", consumer="pantry-orders", sink="order_items", collection="pantry")
    def order_items(photo, sz):
        for upc in photo["barcodes"]:
            yield upc, {"upc": upc, "photo": photo["token"]}

    run()        # --once (default) | --follow | --rewind | --dry-run

Delivery is at-least-once with latest-value semantics (feedz model): a row that changes is
re-delivered with a higher feed_sync_id, deletes arrive as entries with "deleted_at" set, and the
cursor is committed only after a whole page was handled. Handlers must therefore be idempotent.
Writing derived state with put_record() is: a value-identical put is a server-side no-op.

Image transform workers (see transform_examples.py):

    from snapz import image_transform, run, Skip, Halt

    @image_transform("dominant-color", kind="extract", description="average RGB")
    def dominant(intent, image, sz):          # image = bytes of the version the intent reads
        return {"rgb": [...]}                 # result data; or (data, meta_patch)

    @image_transform("gray", kind="rewrite")
    def gray(intent, image, sz):
        return new_image_bytes                # becomes a child version; or (bytes, data)

    run()

Each worker consumes the `intents` feed for its transform and posts a result. Raise Skip("why")
when a photo doesn't apply (the chain continues) or Halt("why") to stop the collection's chain.

CLI:

    snapz.py status                      feeds, sequences, consumer lag
    snapz.py ls [COLLECTION]             collections, or photos in one
    snapz.py tail FEED [--follow] [-f key=value ...]      print entries as JSON lines
    snapz.py upload COLLECTION FILE...   [--note N] [--barcode UPC]
    snapz.py records [NS [PREFIX]]
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


class SnapzError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status


def _config():
    """KEY=VALUE lines from $XDG_CONFIG_HOME/snapz/env (default ~/.config/snapz/env), if present."""
    path = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "snapz", "env")
    out = {}
    try:
        with open(path) as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.removeprefix("export ").split("=", 1)
                    out[k.strip()] = v.strip().strip("'\"")
    except OSError:
        pass
    return out


class Snapz:
    def __init__(self, url=None, token=None, timeout=30):
        cfg = _config()
        self.url = (url or os.environ.get("SNAPZ_URL") or cfg.get("SNAPZ_URL") or "http://localhost:8788").rstrip("/")
        self.token = token or os.environ.get("SNAPZ_TOKEN") or cfg.get("SNAPZ_TOKEN")
        self.timeout = timeout
        if not self.token:
            raise SystemExit("SNAPZ_TOKEN is not set (env or ~/.config/snapz/env; mint one in the web UI: tokens tab)")

    # ---- plumbing
    def call(self, method, path, body=None, raw=None, query=None, timeout=None):
        if query:
            path += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        data = None
        if raw is not None:
            data, headers["Content-Type"] = raw, "application/octet-stream"
        elif body is not None:
            data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
        req = urllib.request.Request(self.url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                text = r.read()
        except urllib.error.HTTPError as e:
            raise SnapzError(e.code, e.read().decode(errors="replace")) from None
        return json.loads(text) if text else None

    @staticmethod
    def _q(s):
        return urllib.parse.quote(str(s), safe="")

    # ---- collections / photos
    def collections(self):
        return self.call("GET", "/api/collections")["collections"]

    def create_collection(self, name, slug=None, description=None, icon=None):
        return self.call("POST", "/api/collections", {"name": name, "slug": slug, "description": description, "icon": icon})

    def photos(self, collection=None, barcode=None, deleted=False, page=300):
        """All matching photos, newest first (pages transparently)."""
        before = None
        while True:
            r = self.call("GET", "/api/photos", query={
                "collection": collection, "barcode": barcode, "limit": page, "before": before, "deleted": "true" if deleted else None})
            yield from r["photos"]
            before = r.get("next_before")
            if not before:
                return

    def photo(self, token):
        return self.call("GET", f"/api/photos/{token}")

    def upload(self, collection, data, taken_at=None, note=None, meta=None, client_id=None):
        return self.call("POST", "/api/photos", raw=data, timeout=120, query={
            "collection": collection, "taken_at": taken_at, "note": note, "client_id": client_id,
            "meta": json.dumps(meta) if meta else None})

    def update_photo(self, token, **fields):  # note=, meta=, taken_at=, collection= (move)
        return self.call("PATCH", f"/api/photos/{token}", fields)

    def delete_photo(self, token):
        return self.call("DELETE", f"/api/photos/{token}")

    def blob(self, token, thumb=False):
        req = urllib.request.Request(f"{self.url}/blobs/{token}" + ("?size=thumb" if thumb else ""), headers={"Authorization": f"Bearer {self.token}"})
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read()

    # ---- records (derived state; also a feed)
    def get_record(self, ns, k, default=None):
        try:
            return self.call("GET", f"/api/records/{self._q(ns)}/{self._q(k)}")["v"]
        except SnapzError as e:
            if e.status == 404:
                return default
            raise

    def put_record(self, ns, k, v):
        """Upsert; the body is the JSON value itself. Value-identical puts don't republish."""
        return self.call("PUT", f"/api/records/{self._q(ns)}/{self._q(k)}", raw=json.dumps(v).encode())

    def delete_record(self, ns, k):
        try:
            self.call("DELETE", f"/api/records/{self._q(ns)}/{self._q(k)}")
            return True
        except SnapzError as e:
            if e.status == 404:
                return False
            raise

    def records(self, ns, prefix=None, limit=5000):
        return self.call("GET", f"/api/records/{self._q(ns)}", query={"prefix": prefix, "limit": limit})["records"]

    # ---- transforms
    def register_transform(self, name, kind, description=None):
        self.call("PUT", f"/api/transforms/{self._q(name)}", {"kind": kind, "description": description})

    def request_transform(self, token, transform, params=None):
        return self.call("POST", f"/api/photos/{token}/intents", {"transform": transform, "params": params})

    def history(self, token):
        return self.call("GET", f"/api/photos/{token}/history")

    def post_result(self, intent, status="ok", data=None, meta_patch=None, error=None, worker=None):
        return self.call("POST", f"/api/intents/{intent}/results", {"status": status, "data": data, "meta_patch": meta_patch, "error": error, "worker": worker})

    def post_output(self, intent, image, data=None, worker=None, current=True):
        return self.call("POST", f"/api/intents/{intent}/output", raw=image, timeout=120, query={
            "worker": worker, "data": json.dumps(data) if data is not None else None, "current": None if current else "false"})

    def input_image(self, intent):
        """Bytes of the exact version an intent (a feed entry) was issued against."""
        req = urllib.request.Request(self.url + intent["input_url"], headers={"Authorization": f"Bearer {self.token}"})
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read()

    def inbox(self):
        """[{"photo": …, "suggestions": [{"collection", "p"}, …], "features": {…}}], newest first."""
        return self.call("GET", "/api/inbox")["photos"]

    # ---- feeds
    def fetch(self, feed, after=0, limit=100, **filters):
        """One page: {"entries": [...], "cursor": N}. filters: collection= (photos), ns= (records), shard=/shard_count=."""
        return self.call("GET", f"/_feeds/fetch/{feed}", query={"after": after, "limit": limit, **filters})

    def cursor(self, consumer, feed, shard=0):
        return self.call("GET", f"/_feeds/cursors/{self._q(consumer)}", query={"feed": feed, "shard": shard})["cursor"]

    def set_cursor(self, consumer, feed, cursor, shard=0, shard_count=1):
        self.call("PUT", f"/_feeds/cursors/{self._q(consumer)}", {"feed": feed, "cursor": cursor, "shard": shard, "shard_count": shard_count})

    def status(self):
        return self.call("GET", "/_feeds/status")

    def consume(self, feed, consumer, handler, *, follow=False, poll_s=2.0, batch=100, commit=True, log=None, **filters):
        """Run handler(entry) over everything past `consumer`'s stored cursor.

        The cursor lives on the server (named by `consumer`), so the script itself is stateless and
        can run from cron, from a laptop, wherever. It is advanced after each fully handled page; an
        exception in the handler stops the run with the cursor still before that page.
        Returns the number of entries handled.
        """
        shard, shard_count = filters.get("shard") or 0, filters.get("shard_count") or 1
        pos = self.cursor(consumer, feed, shard) if commit else 0
        n = 0
        while True:
            page = self.fetch(feed, after=pos, limit=batch, **filters)
            for entry in page["entries"]:
                handler(entry)
                n += 1
            if page["cursor"] != pos:
                pos = page["cursor"]
                if commit:
                    self.set_cursor(consumer, feed, pos, shard, shard_count)
                if log:
                    log(f"{consumer}: {feed} @ {pos} (+{len(page['entries'])})")
            if len(page["entries"]) == batch:
                continue  # more backlog: don't sleep
            if not follow:
                return n
            time.sleep(poll_s)


# ---- transforms: feed -> function -> records namespace -------------------------------------

_TRANSFORMS = []


def transform(feed, consumer, sink=None, **filters):
    """Register fn(entry, sz) as a stream processor over `feed`.

    fn may return/yield nothing (side effects only) or (key, value) pairs, which are upserted into
    the `sink` records namespace; a (key, None) pair deletes that record. Because records are
    themselves a feed, another transform can consume @transform("records", ..., ns=sink).
    """
    def deco(fn):
        _TRANSFORMS.append({"fn": fn, "feed": feed, "consumer": consumer, "sink": sink, "filters": filters})
        return fn
    return deco


class Skip(Exception):
    """Raised by an image transform: not applicable to this photo. The chain continues."""


class Halt(Exception):
    """Raised by an image transform: done, and the collection's chain stops here."""


_IMAGE_TRANSFORMS = []


def image_transform(name, kind="extract", description=None, current=True):
    """Register fn(intent, image_bytes, sz) as the worker for transform `name`.

    extract: return data, or (data, meta_patch) to also merge keys into photo.meta.
    rewrite: return image bytes, or (bytes, data). `current=False` adds the version without
             making it the photo's current one (do that for anything that changes geometry).
    """
    if kind not in ("extract", "rewrite"):
        raise ValueError("kind must be extract or rewrite")

    def deco(fn):
        _IMAGE_TRANSFORMS.append({"fn": fn, "name": name, "kind": kind, "description": description or (fn.__doc__ or "").strip() or None, "current": current})
        return fn
    return deco


def _work(t, sz, worker, dry_run, log):
    def handle(intent):
        # The feed is at-least-once and replayable: answer only what hasn't been answered.
        if intent["state"] != "pending" or not intent.get("input_url"):
            return
        label = f"{t['name']} {intent.get('collection')}/{intent['photo']}"
        if dry_run:
            log(f"would run {label}")
            return
        try:
            out = t["fn"](intent, sz.input_image(intent), sz)
            if t["kind"] == "rewrite":
                image, data = out if isinstance(out, tuple) else (out, None)
                sz.post_output(intent["token"], image, data=data, worker=worker, current=t["current"])
            else:
                data, patch = out if isinstance(out, tuple) else (out, None)
                sz.post_result(intent["token"], data=data, meta_patch=patch, worker=worker)
            log(f"ok   {label}")
        except Skip as e:
            sz.post_result(intent["token"], status="skipped", data={"reason": str(e)}, worker=worker)
            log(f"skip {label}: {e}")
        except Halt as e:
            sz.post_result(intent["token"], status="halt", data={"reason": str(e)}, worker=worker)
            log(f"halt {label}: {e}")
        except Exception as e:  # a failed photo must not wedge the worker; the error is the result
            sz.post_result(intent["token"], status="error", error=f"{type(e).__name__}: {e}", worker=worker)
            log(f"ERR  {label}: {e}")
    return handle


def _pairs(result):
    if result is None:
        return []
    if isinstance(result, tuple) and len(result) == 2 and isinstance(result[0], str):
        return [result]
    return list(result)


def run(argv=None):
    """Entry point for a script that defines @transform functions."""
    ap = argparse.ArgumentParser(description="run the transforms defined in this script")
    ap.add_argument("--follow", action="store_true", help="keep polling instead of exiting when caught up")
    ap.add_argument("--poll", type=float, default=2.0, metavar="SECONDS")
    ap.add_argument("--rewind", action="store_true", help="reset cursors to 0 first: reprocess the whole feed")
    ap.add_argument("--dry-run", action="store_true", help="print what would be emitted; skip sink writes and cursor commits")
    ap.add_argument("--only", metavar="CONSUMER", help="run just this consumer")
    args = ap.parse_args(argv)
    sz = Snapz()
    todo = [t for t in _TRANSFORMS if not args.only or t["consumer"] == args.only]
    workers = [t for t in _IMAGE_TRANSFORMS if not args.only or t["name"] == args.only]
    if not todo and not workers:
        raise SystemExit("no transforms registered")
    worker_id = f"{os.uname().nodename}:{os.path.basename(sys.argv[0])}"

    def log(msg):
        print(time.strftime("%Y-%m-%dT%H:%M:%S%z"), msg, file=sys.stderr, flush=True)

    def handler_for(t):
        def handle(entry):
            for k, v in _pairs(t["fn"](entry, sz)):
                if args.dry_run or not t["sink"]:
                    log(f"{t['consumer']}: {'would emit' if t['sink'] else 'no sink for'} {t['sink'] or ''}/{k} = {json.dumps(v)}")
                    continue
                if v is None:
                    sz.delete_record(t["sink"], k)
                else:
                    sz.put_record(t["sink"], k, v)
        return handle

    for t in todo:
        if args.rewind and not args.dry_run:
            sz.set_cursor(t["consumer"], t["feed"], 0)
            log(f"{t['consumer']}: rewound {t['feed']} to 0")
    for t in workers:
        if not args.dry_run:
            sz.register_transform(t["name"], t["kind"], t["description"])
            if args.rewind:
                sz.set_cursor(f"worker:{t['name']}", "intents", 0)
    while True:
        total = 0
        for t in todo:
            total += sz.consume(t["feed"], t["consumer"], handler_for(t), commit=not args.dry_run, log=log, **t["filters"])
        for t in workers:
            total += sz.consume("intents", f"worker:{t['name']}", _work(t, sz, worker_id, args.dry_run, log),
                                commit=not args.dry_run, transform=t["name"])
        if not args.follow:
            log(f"caught up ({total} entries)")
            return
        time.sleep(args.poll)


# ---- CLI ------------------------------------------------------------------------------------

def _main():
    ap = argparse.ArgumentParser(description="snapz command line", epilog="env: SNAPZ_URL, SNAPZ_TOKEN")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status")
    p = sub.add_parser("ls"); p.add_argument("collection", nargs="?")
    p = sub.add_parser("tail"); p.add_argument("feed"); p.add_argument("--follow", action="store_true")
    p.add_argument("--after", type=int, default=0); p.add_argument("-f", "--filter", action="append", default=[], metavar="KEY=VALUE")
    p = sub.add_parser("upload"); p.add_argument("collection"); p.add_argument("files", nargs="+")
    p.add_argument("--note"); p.add_argument("--barcode", action="append", default=[])
    p = sub.add_parser("records"); p.add_argument("ns", nargs="?"); p.add_argument("prefix", nargs="?")
    a = ap.parse_args()
    sz = Snapz()
    if a.cmd == "status":
        st = sz.status()
        print(f"{'feed':<12} {'sequence':>9} {'rows':>7} {'pending':>8}")
        for f in st["feeds"]:
            print(f"{f['feed']:<12} {f['sequence']:>9} {f['rows']:>7} {f['pending']:>8}")
        print(f"\n{'consumer':<24} {'feed':<12} {'cursor':>8} {'lag':>6}  updated")
        for c in st["cursors"]:
            print(f"{c['consumer']:<24} {c['feed']:<12} {c['cursor']:>8} {c.get('lag', ''):>6}  {c['updated_at']}")
    elif a.cmd == "ls":
        if a.collection:
            for ph in sz.photos(a.collection):
                print(f"{ph['taken_at']}  {ph['token']}  {ph['bytes']:>9}  {' '.join(ph['barcodes']):<14} {ph.get('note', '')}")
        else:
            for c in sz.collections():
                print(f"{c['slug']:<24} {c['photo_count']:>6} {c['bytes']:>12}  {c.get('last_photo_at', '')}")
    elif a.cmd == "tail":
        filters = dict(f.split("=", 1) for f in a.filter)
        pos = a.after
        while True:
            page = sz.fetch(a.feed, after=pos, **filters)
            for e in page["entries"]:
                print(json.dumps(e), flush=True)
            more = len(page["entries"]) == 100
            pos = page["cursor"]
            if not more:
                if not a.follow:
                    break
                time.sleep(2)
    elif a.cmd == "upload":
        meta = {"source": "cli"}
        if a.barcode:
            meta["barcodes"] = [{"value": b} for b in a.barcode]
        for path in a.files:
            with open(path, "rb") as fh:
                ph = sz.upload(a.collection, fh.read(), note=a.note, meta={**meta, "filename": os.path.basename(path)},
                               taken_at=int(os.path.getmtime(path) * 1000))
            print(ph["token"], path)
    elif a.cmd == "records":
        if not a.ns:
            for n in sz.call("GET", "/api/records")["namespaces"]:
                print(f"{n['ns']:<24} {n['count']:>6}  {n.get('updated_at', '')}")
        else:
            for r in sz.records(a.ns, a.prefix):
                print(r["k"], json.dumps(r["v"]), sep="\t")


if __name__ == "__main__":
    try:
        _main()
    except (SnapzError, BrokenPipeError, KeyboardInterrupt) as e:
        raise SystemExit(str(e) or 1)
