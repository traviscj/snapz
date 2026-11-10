# snapz

Photos that never touch the iPhone library: shoot into named collections (pantry, food, work,
illness…), browse/move/copy/delete from the web, and consume everything as a feedz-style change
feed so small scripts can turn, say, pantry UPC scans into order items.

    ios/      SwiftUI app: camera → on-device barcode detect → durable offline upload queue
    server/   Kotlin + misk: JSON API, blobs on disk, MySQL, OIDC + bearer tokens, feeds, web UI
    scripts/  snapz.py (stdlib client + @transform stream-processor lib), pantry_orders.py
    deploy/   Dockerfile / compose / config templates

## run locally

    mysql -uroot -e 'create database if not exists snapz_dev; create database if not exists snapz_test'
    cd server && . bin/activate-hermit
    ./gradlew test                                   # 13 tests, real HTTP against snapz_test
    cp snapz-local.yaml.example snapz-local.yaml     # gitignored; set a dev token in it
    SNAPZ_CONFIG=$PWD/snapz-local.yaml ./gradlew run # http://localhost:8788

`snapz-local.yaml` defines a `bootstrap_tokens` entry; paste that token on the login page. With `snapz.oidc` configured the page offers SSO instead.

## model

| thing | notes |
|---|---|
| collection | `slug` is the id. Soft-deleted; slug is freed for reuse. |
| photo | row → blob by sha256 (`<blob_dir>/orig/ab/<sha>`, thumbs alongside). **copy** = new row, same blob; **move** = change collection; **delete** = trash (restorable), `POST /api/admin/purge` hard-deletes + GCs unreferenced blobs. `meta` is free-form JSON; `meta.barcodes[].value` is denormalized to `barcodes`. |
| record | generic JSON doc at `(ns, k)` — where transforms write derived state. |
| token | `session` (web cookie), `device` (iOS), `script` (you mint these). SHA-256 hashes only. |

## auth

- **Web**: `/auth/login` → OIDC code flow (+PKCE) against the configured issuer (Pocket ID; any
  OIDC provider incl. Google works) → `HttpOnly; SameSite=Lax` session cookie. Access is limited to
  `allowed_emails` / `allowed_groups`.
- **iOS**: same flow inside `ASWebAuthenticationSession`, ending at `snapz://auth?code=…`; the app
  trades that one-time code + its own PKCE verifier for a long-lived, individually revocable
  device token (Keychain).
- **Scripts**: `Authorization: Bearer snapz_…`, minted in the web UI (tokens tab) or
  `POST /api/tokens {"name": …}`.

## API (JSON, snake_case, ISO-8601 UTC timestamps)

    GET    /api/collections                         POST {name, slug?, description?, icon?}
    GET|PATCH|DELETE /api/collections/{slug}        DELETE ?force=true trashes its photos too
    GET    /api/photos?collection=&barcode=&deleted=&limit=&before=<next_before>
    POST   /api/photos?collection=&taken_at=&note=&meta=<json>&client_id=<idempotency key>
           body = raw image bytes:  curl -H "Authorization: Bearer $T" --data-binary @x.jpg "$U/api/photos?collection=pantry"
    GET|PATCH|DELETE /api/photos/{token}            PATCH {note?, meta?, taken_at?, collection?(=move)}
    POST   /api/photos/{token}/copy {collection}    POST /api/photos/{token}/restore
    POST   /api/photos/batch {op: move|copy|delete|restore, tokens: [...], collection?}   (all-or-nothing)
    GET    /blobs/{token}[?size=thumb]              immutable, ETag'd
    GET    /api/records                             namespaces
    GET    /api/records/{ns}?prefix=                GET|PUT|DELETE /api/records/{ns}/{k}   (PUT body = the JSON value)
    GET|POST /api/tokens                            DELETE /api/tokens/{id}

## feeds

Same model as feedz: the tables are the feed. Every mutation NULLs the row's `feed_sync_id`; a
single publisher (every 500 ms) assigns fresh monotonic ids per feed, so consumers paging
`feed_sync_id > cursor` see each row's latest state at least once. Deletes arrive as entries with
`deleted_at` set.

    GET /_feeds/fetch/{photos|collections|records}?after=N&limit=&collection=&ns=&shard=&shard_count=
        → {"feed": "photos", "entries": [...], "cursor": N'}        # poll again with after=N'
    GET|PUT|DELETE /_feeds/cursors/{consumer}?feed=                  # server-side cursor storage
    GET /_feeds/status                                               # sequences, pending, consumer lag

### transforms

`scripts/snapz.py` is one stdlib-only file: a client, a consumer loop with server-stored cursors,
and a `@transform` decorator (feed → function → records namespace). Records are a feed too, so
transforms chain.

```python
from snapz import run, transform

@transform("photos", consumer="pantry-orders", sink="order_items")
def order_items(photo, sz):
    ...
    yield upc, {"upc": upc, "qty": n, ...}     # (key, None) deletes
run()   # --once | --follow | --rewind | --dry-run
```

`scripts/pantry_orders.py` is the worked example: barcoded photos in `pantry` → `order_items/<upc>`
with name/brand/size from Open Food Facts (cached in `products/`). It is a pure function of the
live scans, so replays are no-ops and deleting/moving a scan retracts it. Known gap: editing a
photo's `meta` to *remove* a barcode doesn't retract the old UPC.

    export SNAPZ_URL=http://localhost:8788 SNAPZ_TOKEN=…     # or ~/.config/snapz/env (chmod 600)
    scripts/pantry_orders.py --follow
    scripts/snapz.py status | ls [coll] | tail photos -f collection=pantry | upload pantry x.jpg --barcode 0016000275287

## iOS app

Needs full Xcode and a signing team. Your team id, bundle id prefix and default server URL go in
`ios/Local.xcconfig` (gitignored):

    cd ios && cp Local.xcconfig.example Local.xcconfig   # edit it
    brew install xcodegen && xcodegen && open Snapz.xcodeproj

- Capture is in-app (AVFoundation) and writes only to the app's own storage — never PhotoKit.
- Each shot is saved to an on-disk queue first, then uploaded oldest-first with its id as
  `client_id`; retries can't duplicate, and nothing is deleted locally until the server acks.
  Uploads run while the app is foregrounded plus the short grace iOS grants after backgrounding
  (not a background URLSession yet).
- Barcodes are detected on the still with Vision; UPC-A is reported as 13-digit EAN-13.
- Verified so far: all sources type-check against the macOS 26 SDK (platform-specific bits are
  isolated in `Compat.swift`), and `API.swift` was exercised end-to-end against a live server.
  **Not yet built for iOS or run on a device** — the `#if os(iOS)` branches and the camera path are
  the unproven part.

## deploy

`deploy/` has a Dockerfile, a compose file and config templates. Everything host-specific stays out
of git:

    cp deploy/.env.example deploy/.env                  # blob dir, bind address, subnet
    cp deploy/snapz.yaml.example deploy/snapz.yaml      # public_url, DB, OIDC client + allowlist (chmod 600)

You need an OIDC client whose callback is `<public_url>/auth/callback`, a TLS reverse proxy in
front of port 8788, a `snapz` MySQL database, and a blob directory that gets backed up. If the
identity provider is only reachable on your LAN/VPN, signing in from the phone needs that network;
capture itself works anywhere (offline queue).
