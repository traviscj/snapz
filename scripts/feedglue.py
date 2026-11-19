#!/usr/bin/env python3
"""feedglue: tie a feed on one snapz server to the Receive endpoint of another.

    source   a server whose feeds are read          (/_feeds/fetch/{feed})
    sink     a server that takes those entries       (/_feeds/receive/{feed})
    glue     source + optional filters + sink, per feed, with a cursor, a page size and an interval

One step of a glue, per feed:

    read cursor -> fetch a page from the source -> filter -> receive at the sink -> write cursor

The cursor only moves after the sink accepted the page, so a failure anywhere means the same page
is delivered again: at-least-once. Receive is idempotent, which makes that safe. Photo bytes are
not in the feed; when the sink says it lacks a blob, the glue copies it by hash and re-sends.

Config (JSON; tokens are named by environment variable, never written in the file):

    {
      "sources": {"home": {"url": "https://snapz.home.example", "token_env": "SNAPZ_HOME_TOKEN"}},
      "sinks":   {"pub":  {"url": "https://snapz.example.net",  "token_env": "SNAPZ_PUB_TOKEN"}},
      "glues": [
        {"name": "home-to-pub", "source": "home", "sink": "pub",
         "feeds": ["vaults", "collections", "photos", "records"],
         "limit": 100, "interval_s": 5,
         "filters": [{"ignore_if": {"field": "vault", "equals": "work"}}]}
      ]
    }

The source token may be read-only (not vault-pinned); the sink token needs admin. Cursors live on
the source, under the consumer name "glue:<name>".

    feedglue.py run    [--config glue.json] [--glue NAME] [--follow] [--dry-run]
    feedglue.py status [--config glue.json]
    feedglue.py rewind NAME [--config glue.json]        # start that glue over from 0

Stdlib only; needs snapz.py next to it.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from snapz import Snapz, SnapzError  # noqa: E402

FEEDS = ["vaults", "collections", "photos", "records"]   # what a snapz server can receive


def log(msg):
    print(f"{datetime.now().astimezone().isoformat(timespec='seconds')} {msg}", flush=True)


def endpoint(spec, what):
    token = os.environ.get(spec.get("token_env", ""))
    if not token:
        raise SystemExit(f"{what}: environment variable {spec.get('token_env')!r} is not set")
    return Snapz(url=spec["url"], token=token, timeout=spec.get("timeout_s", 60))


def blob(sz, sha):
    """Bytes by content hash, or None when the server no longer has them."""
    req = urllib.request.Request(f"{sz.url}/_feeds/blobs/{sha}", headers={"Authorization": f"Bearer {sz.token}"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise SnapzError(e.code, e.read().decode(errors="replace")) from None


def field(entry, path):
    for part in path.split("."):
        entry = entry.get(part) if isinstance(entry, dict) else None
    return entry


def keep(entry, filters):
    """filters: [{"ignore_if": {"field": "a.b", "equals": v}}, {"retain_if": {...}}], applied in order."""
    for f in filters:
        (kind, cond), = f.items()
        hit = field(entry, cond["field"]) == cond.get("equals")
        if (kind == "ignore_if" and hit) or (kind == "retain_if" and not hit):
            return False
    return True


class Glue:
    def __init__(self, spec, config):
        self.name = spec["name"]
        self.source = endpoint(config["sources"][spec["source"]], f"source {spec['source']}")
        self.sink = endpoint(config["sinks"][spec["sink"]], f"sink {spec['sink']}")
        self.feeds = spec.get("feeds", FEEDS)
        self.limit = int(spec.get("limit", 100))
        self.interval = float(spec.get("interval_s", 5))
        self.filters = spec.get("filters", [])
        self.consumer = f"glue:{self.name}"
        self.next_at = 0.0

    def receive(self, feed, entries):
        """Deliver one page. Returns when the sink holds all of it; raises to have it redelivered."""
        for attempt in (1, 2):
            resp = self.sink.call("POST", f"/_feeds/receive/{feed}", {"entries": entries}, timeout=120)
            missing = resp["missing_blobs"]
            if not missing:
                return resp
            if attempt == 2:
                raise RuntimeError(f"sink still lacks {len(missing)} blob(s) after copying them")
            gone = set()
            for m in missing:
                data = blob(self.source, m["sha256"])
                if data is None:
                    # Purged at the source, or the photo has moved on to new bytes: a later entry describes what there is now.
                    log(f"{self.name}: {feed} {m['token']} blob {m['sha256'][:10]} is gone at the source; skipping this entry")
                    gone.add((m["token"], m["sha256"]))
                    continue
                self.sink.call("PUT", f"/_feeds/blobs/{m['sha256']}", raw=data, timeout=300)
            entries = [e for e in entries if (e.get("token"), e.get("sha256")) not in gone]
            if not entries:
                return resp
        return resp

    def step(self, feed, dry_run=False):
        """One page of one feed. Returns entries fetched (0 = caught up)."""
        pos = self.source.cursor(self.consumer, feed)
        page = self.source.fetch(feed, after=pos, limit=self.limit)
        fetched = page["entries"]
        if page["cursor"] == pos:
            return 0
        entries = [e for e in fetched if keep(e, self.filters)]
        if dry_run:
            log(f"{self.name}: {feed} @ {pos} would deliver {len(entries)}/{len(fetched)} -> {page['cursor']}")
            return 0   # the cursor did not move: asking again would return the same page
        if entries:
            r = self.receive(feed, entries)
            outcome = f"applied {r['applied']} unchanged {r['unchanged']} stale {r['stale']}"
        else:
            outcome = "all filtered"
        self.source.set_cursor(self.consumer, feed, page["cursor"])
        log(f"{self.name}: {feed} @ {pos} -> {page['cursor']} (+{len(fetched)}; {outcome})")
        return len(fetched)

    def drain(self, dry_run=False):
        n = 0
        for feed in self.feeds:
            while True:
                got = self.step(feed, dry_run)
                n += got
                if got < self.limit:
                    break
        return n


def load(path):
    with open(path) as fh:
        config = json.load(fh)
    return config, [Glue(g, config) for g in config["glues"]]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("command", choices=["run", "status", "rewind"])
    ap.add_argument("name", nargs="?")
    ap.add_argument("--config", default=os.environ.get("FEEDGLUE_CONFIG", "glue.json"))
    ap.add_argument("--glue", help="only this glue")
    ap.add_argument("--follow", action="store_true", help="keep running on each glue's interval")
    ap.add_argument("--dry-run", action="store_true", help="fetch and report; deliver nothing, move no cursor")
    a = ap.parse_args(argv)
    _, glues = load(a.config)
    only = a.glue or a.name
    if only:
        glues = [g for g in glues if g.name == only]
        if not glues:
            raise SystemExit(f"no glue named {only!r} in {a.config}")

    if a.command == "status":
        print(f"{'glue':<20} {'feed':<12} {'cursor':>10} {'head':>10} {'lag':>8}")
        for g in glues:
            heads = {f["feed"]: f["sequence"] for f in g.source.status()["feeds"]}
            for feed in g.feeds:
                pos = g.source.cursor(g.consumer, feed)
                print(f"{g.name:<20} {feed:<12} {pos:>10} {heads.get(feed, 0):>10} {heads.get(feed, 0) - pos:>8}")
        return

    if a.command == "rewind":
        if not a.name:
            raise SystemExit("rewind needs a glue name")
        for g in glues:
            for feed in g.feeds:
                g.source.set_cursor(g.consumer, feed, 0)
            log(f"{g.name}: cursors reset to 0 ({', '.join(g.feeds)})")
        return

    while True:
        for g in glues:
            if time.monotonic() < g.next_at:
                continue
            try:
                g.drain(a.dry_run)
            except (SnapzError, RuntimeError, OSError) as e:
                # The cursor did not move; the same page goes again next interval.
                log(f"{g.name}: {type(e).__name__}: {e}")
                if not a.follow:
                    raise SystemExit(1)
            g.next_at = time.monotonic() + g.interval
        if not a.follow:
            return
        time.sleep(max(0.2, min(g.next_at for g in glues) - time.monotonic()))


if __name__ == "__main__":
    main()
