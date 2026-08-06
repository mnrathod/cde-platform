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

**Demo credentials:** `admin` / `admin123` · `engineer1` / `pass123`

### DXF/DWG support

| File type | Behaviour |
|---|---|
| `.dxf` | Rendered via **ezdxf** Python service (full fidelity) |
| `.dwg` | Binary DWG detected — version shown + conversion guide |
| `.svg` | Rendered inline |
| `.png` / `.jpg` | Displayed as image |
| `.pdf` | Rendered in iframe |

The ezdxf converter runs as a lightweight Python HTTP service on port 5001.
If it's not running, the app falls back to the built-in Java DXF parser automatically.

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
