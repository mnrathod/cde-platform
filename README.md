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
export CDE_JWT_SECRET='<a long random string>'
docker compose up --build
```

Then open **http://localhost:8080**.

`CDE_JWT_SECRET` is required and compose refuses to start without it — the
value in `application.yml` is a placeholder, not a default.

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

### Manual startup

**Terminal 1 — Python DXF converter (ezdxf):**
```cmd
REM Windows — always use "python -m pip", never "pip3"
python -m pip install "ezdxf[draw]"
python converter\app.py
```

**Terminal 2 — Spring Boot:**
```cmd
gradle bootRun
```

Then open: **http://localhost:8080**

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

```
cde-platform/
├── src/main/java/com/cde/platform/
│   ├── CdePlatformApplication.java        # Entry point
│   ├── config/
│   │   ├── DataSeeder.java                # Demo data
│   │   └── SecurityConfig.java            # JWT + CORS
│   ├── controller/
│   │   ├── AuthController.java            # /api/auth/login|register
│   │   ├── ProjectController.java         # /api/projects
│   │   ├── DocumentController.java        # /api/documents
│   │   ├── AnnotationController.java      # /api/annotations
│   │   └── ViewerController.java          # /api/viewer/{id}
│   ├── model/                             # JPA entities
│   ├── repository/                        # Spring Data repos
│   ├── dto/                               # Request/response DTOs
│   └── security/                          # JwtUtil, JwtFilter
└── src/main/resources/
    ├── application.yml
    └── static/
        └── index.html                     # Full SPA frontend + 2D viewer
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

## Extending

- **PostgreSQL**: uncomment the driver in `build.gradle` and update `application.yml` datasource
- **PDF support**: integrate Apache PDFBox to render PDF pages to SVG/PNG for the viewer
- **DXF/DWG**: integrate a Java DXF parser (e.g., Kabeja) to convert CAD files to SVG
- **WebSocket**: add real-time annotation collaboration with Spring WebSocket

---

## Commands

```bash
./gradlew bootRun        # Run dev server
./gradlew test           # Run tests
./gradlew bootJar        # Build executable JAR → build/libs/
```
