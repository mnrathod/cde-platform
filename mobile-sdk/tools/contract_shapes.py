"""Describes the payload shapes a running CDE server actually returns.

Invoked by capture-contract.sh, which handles the login. Kept as its own file
rather than embedded in the shell script: the discovery below is more than a
one-liner, and Python quoted inside a bash string is where the last version of
this quietly stopped working — an f-string cannot carry the escaped quotes
that survive shell single-quoting, so every identifier came back empty and the
run reported "no data" for every endpoint as though that were the answer.
"""
import json
import subprocess
import sys

# Substring matched, so accessToken and refreshToken are caught too.
SECRET_HINTS = ("token", "password", "secret", "apikey", "credential")

PAGE_KEYS = {"content", "number", "size", "totalElements", "totalPages", "first", "last"}

# Bounded: this is a description tool, not a crawler.
SEARCH_LIMIT = 25


def get(base, token, path):
    """GETs a path as the authenticated caller, or None if it does not parse."""
    result = subprocess.run(
        ["curl", "-sf", "-H", "Authorization: Bearer " + token, base + path],
        capture_output=True, text=True)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def is_secret(key):
    lowered = key.replace("_", "").lower()
    return any(hint in lowered for hint in SECRET_HINTS)


def show(sample, indent="  "):
    if not isinstance(sample, dict):
        print(f"{indent}(not an object: {type(sample).__name__})")
        return
    for key, value in sample.items():
        kind = type(value).__name__
        if is_secret(key):
            # The type still matters to a client; the value never does. This
            # printed the bearer token it had just been issued, into output
            # whose whole purpose is to be pasted into a diff.
            print(f"{indent}{key}: {kind} = <redacted>")
            continue
        preview = str(value)[:48].replace("\n", " ")
        print(f"{indent}{key}: {kind} = {preview}")


def describe_payload(payload):
    """Prints one payload's field names and types.

    A page envelope is unwrapped as well as described. Reporting the envelope
    under the item's name is precisely the drift this tool exists to catch,
    and it reported it as though DocumentResponse had grown a `content` field.
    """
    if payload is None:
        print("  (no data)")
        return

    if isinstance(payload, list):
        if not payload:
            print("  (EMPTY — nothing on the server to describe; NOT verified)")
            return
        show(payload[0])
        return

    if isinstance(payload, dict) and PAGE_KEYS.issubset(payload.keys()):
        print("  page envelope:")
        show(payload, indent="    ")
        print("  content[] item:")
        items = payload["content"]
        if not items:
            print("    (EMPTY — the page holds nothing; item shape NOT verified)")
        else:
            show(items[0], indent="    ")
        return

    show(payload)


def describe(base, token, label, path):
    print(f"=== {label}  ({path})")
    describe_payload(get(base, token, path))
    print()


def discover(base, token):
    """Finds a project, a document, and a document that actually has markup.

    These used to be a literal id of 1, which only ever worked against a
    server carrying the demo seed data. The annotated document is searched for
    separately and across every project, because describing an endpoint that
    happens to answer with an empty list verifies nothing — and the annotated
    document is rarely the first document of the first project.
    """
    projects = get(base, token, "/api/projects") or []
    project_id = document_id = annotated_id = None

    for project in projects[:SEARCH_LIMIT]:
        page = get(base, token,
                   f"/api/documents/project/{project['id']}?size={SEARCH_LIMIT}") or {}
        documents = page.get("content", [])

        if documents and document_id is None:
            project_id, document_id = project["id"], documents[0]["id"]

        for document in documents:
            if annotated_id is not None:
                break
            if get(base, token, f"/api/annotations/document/{document['id']}"):
                annotated_id = document["id"]

        if document_id is not None and annotated_id is not None:
            break

    if project_id is None and projects:
        project_id = projects[0]["id"]

    return project_id, document_id, annotated_id


def main():
    base, token, username, login_body = sys.argv[1:5]
    project_id, document_id, annotated_id = discover(base, token)

    print(f"Described against project {project_id or 'none'}, "
          f"document {document_id or 'none'}, "
          f"annotated document {annotated_id or 'none'}")
    print()

    # Login is the one POST, and describing it with a GET reported "no data"
    # and quietly omitted the payload every other call depends on.
    print("=== AuthResponse  (/api/auth/login)")
    result = subprocess.run(
        ["curl", "-sf", "-X", "POST", base + "/api/auth/login",
         "-H", "Content-Type: application/json", "-d", login_body],
        capture_output=True, text=True)
    try:
        describe_payload(json.loads(result.stdout))
    except json.JSONDecodeError:
        print("  (no data)")
    print()

    describe(base, token, "ProjectResponse", "/api/projects")

    if document_id is not None:
        describe(base, token, "DocumentResponse",
                 f"/api/documents/project/{project_id}")
        describe(base, token, "ViewerData", f"/api/viewer/{document_id}")
    else:
        print(f"!! No document visible to {username} — DocumentResponse and")
        print("!! ViewerData were NOT verified. Seed one and re-run.\n")

    if annotated_id is not None:
        describe(base, token, "AnnotationResponse",
                 f"/api/annotations/document/{annotated_id}")
    else:
        print(f"!! No annotated document visible to {username} —")
        print("!! AnnotationResponse was NOT verified. Draw markup and re-run.\n")


if __name__ == "__main__":
    main()
