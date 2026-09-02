#!/usr/bin/env bash
# Upload one file to the SeaweedFS fast lane and prove that it landed.
# aws s3 cp has been observed returning 0 after a truncated multipart upload
# left no object behind, so exit status alone is not success: HEAD must match.
set -euo pipefail

ENDPOINT="https://s3.kroune.tech"
ATTEMPTS=3

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <local-file> <s3://bucket/key>" >&2
  exit 2
fi

src=$1
dst=$2
if [ ! -f "$src" ]; then
  echo "::error::S3 upload source does not exist: $src"
  exit 1
fi
if [[ ! "$dst" =~ ^s3://([^/]+)/(.+)$ ]]; then
  echo "::error::invalid S3 destination: $dst"
  exit 2
fi
bucket=${BASH_REMATCH[1]}
key=${BASH_REMATCH[2]}
expected=$(stat -c%s -- "$src")

for attempt in $(seq 1 "$ATTEMPTS"); do
  if [ "$attempt" -gt 1 ]; then
    sleep $((10 * (attempt - 1)))
  fi
  echo "S3 upload $src -> $dst (attempt $attempt/$ATTEMPTS, $expected bytes)"
  rc=0
  aws s3 cp --no-progress "$src" "$dst" --endpoint-url "$ENDPOINT" || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "::warning::S3 upload command failed for $dst (attempt $attempt/$ATTEMPTS, exit $rc)"
    continue
  fi

  actual=$(aws s3api head-object --bucket "$bucket" --key "$key" \
    --endpoint-url "$ENDPOINT" --query ContentLength --output text 2>/dev/null || true)
  if [ "$actual" = "$expected" ]; then
    echo "S3 verified: $dst ($actual bytes)"
    exit 0
  fi
  echo "::warning::S3 upload verification failed for $dst (attempt $attempt/$ATTEMPTS, local=$expected, remote=${actual:-missing})"
done

echo "::error::S3 upload failed for $dst after $ATTEMPTS attempts (local=$expected bytes)"
exit 1
