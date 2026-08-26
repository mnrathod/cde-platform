#!/usr/bin/env bash
# Re-reads the API contract from a running server.
#
# The SDKs bind to what the wire actually carries, not to what the backend
# source suggests it should. Run this after changing any DTO and diff the
# output against API-CONTRACT.md — a field renamed on the server is a field
# every installed copy of the app silently stops reading.
#
#   ./capture-contract.sh <base-url> <username> <password>
#
# Credentials are required rather than defaulted. They used to fall back to
# admin/admin123, which is a real seeded account: a tool that works without
# being told a password teaches everyone to leave that account alive.
set -euo pipefail

if [ $# -lt 3 ]; then
  echo "usage: $0 <base-url> <username> <password>" >&2
  echo "  e.g. $0 http://localhost:8080 someone 'their password'" >&2
  exit 2
fi

BASE="$1"
USER="$2"
PASS="$3"
here="$(dirname "$0")"

login_body=$(USERNAME="$USER" PASSWORD="$PASS" python3 -c '
import json, os
print(json.dumps({"username": os.environ["USERNAME"],
                  "password": os.environ["PASSWORD"]}))')

token=$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$login_body" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')

echo "Contract captured from $BASE on $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# The payload shapes are described by a sibling Python file rather than by
# Python quoted inside this script — see the note at the top of that file for
# what quoting did to the previous version.
python3 "$here/contract_shapes.py" "$BASE" "$token" "$USER" "$login_body"

echo "=== enums (from the backend source)"
# No -o here: it suppresses the -A context, so this printed the four enum
# declarations and none of their values — the part anyone actually needs.
grep -rhE 'enum (AnnotationType|AnnotationStatus|DocumentType|DocumentStatus) \{' -A 4 \
  "$here/../../src/main/java" --include='*.java' 2>/dev/null \
  | sed 's/^[[:space:]]*/  /' || echo "  (source not available)"
