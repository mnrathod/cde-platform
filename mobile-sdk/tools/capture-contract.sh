#!/usr/bin/env bash
# Re-reads the API contract from a running server.
#
# The SDKs bind to what the wire actually carries, not to what the backend
# source suggests it should. Run this after changing any DTO and diff the
# output against API-CONTRACT.md — a field renamed on the server is a field
# every installed copy of the app silently stops reading.
set -euo pipefail

BASE="${1:-http://localhost:8080}"
USER="${2:-admin}"
PASS="${3:-admin123}"

token=$(curl -sf -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')

auth=(-H "Authorization: Bearer $token")

# describe <label> <path> [post-body]
# The body argument exists because /api/auth/login is a POST; describing it
# with a GET reported "no data" and quietly omitted the one payload every
# other call depends on.
describe() {
  echo "=== $1  ($2)"
  if [ -n "${3:-}" ]; then
    curl -sf -X POST "$BASE$2" -H 'Content-Type: application/json' -d "$3"
  else
    curl -sf "${auth[@]}" "$BASE$2"
  fi | python3 -c '
import sys, json
payload = json.load(sys.stdin)
# An empty list is the trap here. Reporting "(not an object)" for it reads
# like a shape mismatch when it actually means there was nothing on the
# server to describe — so the field list silently goes missing and the diff
# against API-CONTRACT.md looks clean. Say which it is.
if isinstance(payload, list) and not payload:
    print("  (EMPTY — nothing on the server to describe; this endpoint was NOT verified)")
    raise SystemExit
sample = payload[0] if isinstance(payload, list) else payload
if not isinstance(sample, dict):
    print(f"  (not an object: {type(sample).__name__})"); raise SystemExit
for key, value in sample.items():
    kind = type(value).__name__
    preview = str(value)[:48].replace("\n", " ")
    print(f"  {key}: {kind} = {preview}")
' || echo "  (no data)"
  echo
}

echo "Contract captured from $BASE on $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo
describe "AuthResponse"       "/api/auth/login" "{\"username\":\"$USER\",\"password\":\"$PASS\"}"
describe "ProjectResponse"   "/api/projects"
describe "DocumentResponse"  "/api/documents/project/1"
describe "ViewerData"        "/api/viewer/1"
describe "AnnotationResponse" "/api/annotations/document/${ANNOTATED_DOC:-1}"

echo "=== enums (from the backend source)"
grep -rhoE 'enum (AnnotationType|AnnotationStatus|DocumentType|DocumentStatus) \{' -A 4 \
  "$(dirname "$0")/../../src/main/java" --include='*.java' 2>/dev/null \
  | tr -s ' \n' ' ' | sed 's/--/\n/g' || echo "  (source not available)"
