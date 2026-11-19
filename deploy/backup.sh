#!/bin/sh
# Nightly backup: a consistent dump of the database, then the blob directory, into $DEST.
# The dump is taken first, so every photo row in it has its bytes in the copy that follows
# (blobs are content-addressed and only ever added, except by an explicit purge).
#
#   SNAPZ_DB=snapz SNAPZ_BLOB_DIR=/srv/snapz/blobs DEST=/mnt/backup/snapz ./backup.sh
#   DEST may be remote for the blobs (user@host:/path) if DUMP_DIR is a local directory.
#
# MySQL credentials come from ~/.my.cnf ([client] user=… password=…), never the command line.
set -eu
: "${SNAPZ_DB:=snapz}" "${SNAPZ_BLOB_DIR:?set SNAPZ_BLOB_DIR}" "${DEST:?set DEST}"
DUMP_DIR="${DUMP_DIR:-$DEST/db}"
KEEP_DAYS="${KEEP_DAYS:-30}"
stamp=$(date -u +%Y%m%dT%H%M%SZ)

mkdir -p "$DUMP_DIR"
tmp="$DUMP_DIR/.snapz-$stamp.sql.gz.part"
mysqldump --single-transaction --routines --triggers "$SNAPZ_DB" | gzip > "$tmp"
gzip -t "$tmp"
mv "$tmp" "$DUMP_DIR/snapz-$stamp.sql.gz"
find "$DUMP_DIR" -name 'snapz-*.sql.gz' -mtime +"$KEEP_DAYS" -delete

# No --delete: a purge on the server must not reach back into the backup.
rsync -a --exclude 'thumb/' --exclude '*.tmp-*' "$SNAPZ_BLOB_DIR"/ "$DEST/blobs/"
echo "snapz backup $stamp ok: $(du -sh "$DUMP_DIR/snapz-$stamp.sql.gz" | cut -f1) dump"
