# 🏗 CDE Platform — Common Data Environment

A full-stack Spring Boot application providing a Common Data Environment (CDE) for construction and engineering projects with integrated **2D drawing viewer**.

---

## Features

| Feature | Details |
|---|---|
| **Project Management** | Create/manage projects with phases (Concept → Design → Construction → Handover → Operation) |
| **Document Control** | Upload drawings, specs, reports with revision tracking and status workflow |
| **2D Viewer** | Pan, zoom, rotate SVG/vector drawings inline — no plugins required |
| **Annotations** | Add comments, markups, dimensions, and stamps on drawings |
| **JWT Auth** | Role-based access (Admin, Engineer, Reviewer, Viewer) |
| **REST API** | Full OpenAPI-compatible REST API |

---

## Quick Start

### Docker (recommended — brings the full toolchain with it)

```bash
export CDE_JWT_SECRET=$(openssl rand -base64 48)
export CDE_STORAGE_SIGNING_KEY=$(openssl rand -base64 32)
export POSTGRES_PASSWORD=$(openssl rand -base64 24)
docker compose up --build
```

This starts the **API**, not the user interface. The interface is the Angular
application in the `cde-angular` repository, which is not built into this
image — `Dockerfile` produces the Spring Boot jar and nothing else. To use the
product, start the frontend alongside (see below); `http://localhost:8080` on
its own answers `401 Not authenticated` on every path, which is correct
behaviour for an API with no anonymous surface.

All three are required and have no defaults. Compose refuses to start without
`CDE_JWT_SECRET` or `POSTGRES_PASSWORD`; the application itself refuses to
start without `CDE_STORAGE_SIGNING_KEY`, which signs the time-limited download
URLs — a shipped default would be a published key able to mint a URL for any
object in any tenant.

Generate them once and keep them, or the database volume and every issued
token become unreadable on the next start. `export` in a shell does not
survive a new terminal, so the durable way to keep them is a `.env`:

```bash
cp .env.example .env      # then fill it in; compose reads it automatically
```

`.env` is gitignored and `.env.example` ships no values — a template with a
working default is how a development key reaches production.

This runs two containers:

| Service | Contains |
|---|---|
| `cde-app` | Spring Boot API |
| `converter` | Python service **plus LibreOffice, LibreDWG and Tesseract** |

The toolchain lives in the converter image because `converter/app.py` shells
out to those three binaries. Running the app without them is what made DWG
and Office silently fail in a container while working on a developer machine.
`GET http://localhost:5001/health` reports which were found; the compose
healthcheck uses it, so a bad image is caught at startup rather than on a
user's first upload.

### Windows (recommended — starts both services)

```cmd
start.bat
```

### Manual startup (for iterating on the backend)

Faster than rebuilding the image on every change, but you supply the three
things compose otherwise provides: a database, the converter, and the secrets.

**Terminal 1 — PostgreSQL.** There is no in-memory fallback; Row-Level Security
is a security control the application depends on, and H2 does not implement it.

`docker-compose.yml` does not publish 5432 — under full compose only `cde-app`
needs it, and it reaches the database over the compose network. Running the
application on the host is the one case that needs a host port, so it takes
the dev overlay:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db
```

That binds Postgres to `127.0.0.1:5432` only, not to every interface. Plain
`docker compose up db` starts the database but leaves it unreachable from the
host, and `./gradlew bootRun` then fails with `Connection to localhost:5432
refused`.

**Terminal 2 — the converter.** It shells out to LibreOffice, LibreDWG and
Tesseract, so running it from source needs all three on `PATH`. Unless you are
changing `converter/app.py`, run the container instead and skip the toolchain:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d converter
# or, from source, with the toolchain already installed:
python -m pip install -r converter/requirements.txt && python converter/app.py
```

`GET http://localhost:5001/health` reports which binaries were found.

**Terminal 3 — Spring Boot:**

```bash
set -a; source .env; set +a
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
./gradlew bootRun
```

`CDE_JWT_SECRET` and `CDE_STORAGE_SIGNING_KEY` are required and the
application fails fast without them; `.env` is where they live (see Quick
Start).

**The datasource password needs setting explicitly.** `application.yml`
defaults `SPRING_DATASOURCE_USERNAME`/`PASSWORD` to `cde`/`cde`, because a
default that works locally is better than one that silently reaches a real
database. Compose initialises Postgres with your generated
`POSTGRES_PASSWORD` instead, so on the host path the two do not match and you
get a password-authentication failure. The `export` above lines them up; put
`SPRING_DATASOURCE_PASSWORD` in `.env` if you prefer.

Under full compose this does not arise — `docker-compose.yml` passes the same
value to both services.

**Terminal 4 — the user interface.** It is a separate Angular application in
the sibling `cde-angular` repository; the backend serves no HTML at all.

```bash
cd ../cde-angular
npm ci
npm start
```

Then open: **http://localhost:4200**

`cde-angular/proxy.conf.json` forwards `/api` and `/ws` to `localhost:8080`,
so the dev server and the backend above are the same system. Going to
`http://localhost:8080` directly gets `401 Not authenticated` on every path —
that is the API refusing an unauthenticated request, not a fault.

**There are no demo credentials.** A fresh deployment starts empty; register an
account and it creates an organisation with you administering it. Nothing is
visible across organisations, so a second registration sees none of the first's
work.

To seed a demonstration organisation instead, supply your own password — there
is no default, and the application refuses to start if seeding is on without
one:

```bash
export CDE_SEED_ENABLED=true
export CDE_SEED_ADMIN_PASSWORD="$(openssl rand -base64 24)"
```

### DXF/DWG support

| File type | Behaviour |
|---|---|
| `.dxf` | Rendered via **ezdxf** Python service (full fidelity) |
| `.dwg` | Converted to DXF, then rendered — **LibreDWG** by default, **ODA File Converter** when available (higher fidelity, tried first) |
| `.svg` | Rendered inline |
| `.png` / `.jpg` | Displayed as image |
| `.pdf` | Rendered in iframe |

The ezdxf converter runs as a lightweight Python HTTP service on port 5001.
If it's not running, the app falls back to the built-in Java DXF parser automatically.

**Running outside Docker?** Binary DWG needs `dwg2dxf` on `PATH`, and LibreDWG
is in no Debian or Ubuntu archive — build it from source, or use the Docker
setup above, which does that for you.

**ODA File Converter** is optional and cannot be bundled: its download is
registration-gated and its licence forbids redistribution. To use it, mount
the extracted install and set `ODA_PATH`:

```yaml
converter:
  volumes:
    - ./vendor/oda:/opt/oda:ro
  environment:
    ODA_PATH: /opt/oda/ODAFileConverter
```

ODA is a Qt application and needs a display even in console mode, so wrap it
with `xvfb-run -a`. Without `ODA_PATH`, DWG falls back to LibreDWG.

---

## Project Structure

Three deployable pieces, in two repositories:

| Piece | Where | Serves |
|---|---|---|
| Backend API | this repo, `src/` | `:8080` — JSON only, no HTML |
| Converter | this repo, `converter/` | `:5001` — CAD/Office/OCR, internal |
| Web interface | **`cde-angular`** (sibling repo) | `:4200` dev, static bundle in production |

The backend serves **no user interface**. There is no `src/main/resources/static`,
and `Dockerfile` builds only the Spring Boot jar. Anything expecting HTML from
`:8080` is looking in the wrong place.

```
cde-platform/
├── src/main/java/com/cde/platform/
│   ├── CdePlatformApplication.java     # Entry point
│   ├── controller/                     # 14 controllers — see REST API below
│   ├── service/                        # Use cases and orchestration
│   ├── model/  repository/  dto/       # JPA entities, Spring Data, DTOs
│   ├── cde/                            # ISO 19650 containers, states, transitions
│   ├── tenancy/                        # Tenant context; feeds PostgreSQL RLS
│   ├── security/  mfa/  invitation/    # JWT, TOTP, invitation redemption
│   ├── audit/                          # Append-only, hash-chained audit log
│   ├── storage/                        # StorageProvider abstraction + local impl
│   ├── upload/                         # Streaming and chunked upload, magic-byte checks
│   ├── ai/                             # Payload sanitiser and per-tenant kill switch
│   ├── deployment/  config/            # Deployment tiers, policy ceilings, security config
│   ├── observability/  health/         # Tracing, metrics, actuator probes
│   ├── openapi/                        # Spec generation and drift gate
│   └── exception/                      # RFC 9457 problem mapping
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/                   # Flyway — owns the schema, including RLS
├── converter/                          # Python service + its Docker toolchain
├── k8s/                                # Manifests and kustomization
└── mobile-sdk/                         # Android and iOS client SDKs
```

---

## REST API

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/auth/register` | Register new user |
| POST | `/api/auth/login` | Login → JWT token |
| GET | `/api/projects` | List all projects |
| POST | `/api/projects` | Create project |
| GET | `/api/documents/project/{id}` | List documents in project |
| POST | `/api/documents/upload` | Upload document (multipart) |
| PATCH | `/api/documents/{id}/status` | Update document status |
| GET | `/api/viewer/{id}` | Get 2D viewer data (SVG/content) |
| GET | `/api/annotations/document/{id}` | Get annotations for doc |
| POST | `/api/annotations` | Add annotation |
| PATCH | `/api/annotations/{id}/resolve` | Resolve annotation |

---

## 2D Viewer Capabilities

- **Pan** — click-drag to navigate large drawings
- **Zoom** — scroll wheel or ± buttons (10%–500%)
- **Rotate** — 90° increments
- **Fit to Screen** — reset viewport
- **Annotations** — comment, markup, cloud, stamp types
- **Annotation panel** — view and resolve all annotations on current drawing
- Supports: **SVG**, PNG, JPG (PDF via browser native)

---

## Documentation

| Document | Covers |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | How the pieces fit, and why |
| [`docs/adr/`](docs/adr/) | Decisions, with the options rejected |
| [`docs/configuration.md`](docs/configuration.md) | Every setting: type, default, range, whether it is a secret |
| [`docs/integration-guide.md`](docs/integration-guide.md) | Embedding the viewer, using the SDKs |
| [`docs/licences.md`](docs/licences.md) | Approved licences, exceptions in force, known gaps |
| [`docs/compliance/`](docs/compliance/) | Control matrix, ROPA, obligations register |
| [`docs/accessibility/`](docs/accessibility/) | Accessibility statement and conformance report |
| [`api/openapi.yaml`](api/openapi.yaml) | Generated spec; interactive docs at `/api/docs` |

This section previously listed PostgreSQL, PDFBox, CAD conversion and
WebSocket collaboration as things to add. All four shipped; the list had
become a description of the past.

---

## Commands

```bash
./gradlew bootRun        # Run dev server
./gradlew test           # Run tests
./gradlew bootJar        # Build executable JAR → build/libs/
```

---

## Dependency updates

Everything is pinned: Gradle to `gradle.lockfile`, Python to a generated
closure, container images to `@sha256` digests, npm to `package-lock.json`.
`scripts/check-pinning.sh` fails the build if any of that comes loose, and it
runs before the build stage so the rest of the pipeline is testing the
artifact we intend to ship.

**Renovate** (`renovate.json` in both repositories) raises the upgrade PRs.
Two of the four ecosystems need a follow-up commit, because Renovate edits the
manifest but cannot regenerate the lock:

```bash
./gradlew resolveAndLockAll --write-locks   # after any Gradle change
converter/lock-requirements.sh              # after any converter/*.in change
```

The Python one needs **python3.12** — the version `ubuntu:24.04` ships, which
is the version the image resolves against. pip resolves against whichever
interpreter runs it, so locking on 3.11 pins numpy 2.4 while the image
installs 2.5; the script refuses to run on the wrong minor rather than write a
lockfile that disagrees with the thing it locks.

`converter/requirements.txt` and `requirements-dev.txt` are **generated** —
edit the matching `.in` and re-run the script.
