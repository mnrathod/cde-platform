# DWG File Comparison — Technical Implementation Summary

**Project:** CDE Platform  
**Feature:** Drawing Comparison Engine  
**Audience:** CTO  
**Date:** June 2026

---

## Overview

The DWG/DXF comparison feature performs **structural entity-level analysis** of engineering drawings without requiring any external cloud service or paid API. It runs entirely on-premise within the CDE platform's existing microservice architecture.

---

## Architecture

```
Browser (React SPA)
    │  POST /api/compare  {documentId1, documentId2}
    ▼
Spring Boot  (CompareController.java)
    │  POST http://localhost:5001/compare  {path1, path2, contentType1, contentType2}
    ▼
Python Microservice  (converter/app.py)
    │  ODA File Converter  (DWG → DXF)   ← only for binary DWG
    │  ezdxf 1.4.4         (DXF parsing)
    ▼
Structured JSON change report  →  Spring Boot  →  Browser
    │  (optional)
    ▼
Claude / OpenAI API  (AI narrative summary)
```

---

## DWG → DXF Conversion Layer

Binary DWG files cannot be read directly by open-source libraries. The pipeline handles this transparently:

1. **ODA File Converter** (free CLI tool, Open Design Alliance) converts `.dwg` → `.dxf`
2. Conversion is attempted in version fallback order: `ACAD2018 → ACAD2013 → ACAD2010 → ACAD2007`
3. Isolated temp directories under `C:\Temp\` (short paths, no spaces) prevent filename collisions
4. The resulting DXF is passed to ezdxf for parsing — binary DWG never touches Python directly

DXF files skip Step 1 entirely and are parsed directly by ezdxf.

---

## Entity Extraction (`_analyze_dxf`)

Each drawing is parsed into a structured fingerprint by iterating the **modelspace** entities:

| Extracted Data | DXF Source | Used For |
|---|---|---|
| Entity counts by type | `e.dxftype()` | Geometry diff |
| Entity counts by layer | `e.dxf.layer` | Layer diff |
| Block reference counts | `INSERT` entities + `e.dxf.name` | Symbol diff (doors, windows) |
| Text/annotation strings | `TEXT`, `MTEXT` entities | Label diff |
| Dimension values | `DIMENSION.actual_measurement` | Annotation diff |
| Hatch count | `HATCH` entities | Fill area diff |
| Layer names | All entity layers | Layer structure diff |
| Block definitions | `doc.blocks` | Symbol library diff |

---

## Comparison Engine (`_compare_cad`)

Both drawings are fingerprinted independently, then diffed across **five analysis dimensions**:

### 1. Geometry Changes
Entity counts are compared type-by-type across 16 entity types (LINE, CIRCLE, ARC, LWPOLYLINE, SPLINE, ELLIPSE, DIMENSION, HATCH, etc.). Severity is classified as `high` (Δ≥5), `medium` (Δ≥2), or `low` (Δ=1).

### 2. Symbol / Block Changes
`INSERT` entity counts are compared per block name. Block names are matched against keyword dictionaries to produce human-readable labels:

```python
kind = 'door'    if any(k in name for k in ['DOOR','DR','DOR'])   else
       'window'  if any(k in name for k in ['WIND','WIN','WDW'])  else
       'column'  if any(k in name for k in ['COL','COLUMN'])      else
       'stair'   if any(k in name for k in ['STAIR','STEP'])      else
       f"'{block}' symbol"
```

This produces outputs like: *"Removed 2 doors (Block 'DOOR-FD30': 3 → 1)"*

### 3. Layer Structure Changes
Set difference between layer name lists identifies added/removed layers, with entity counts per layer reported for context.

### 4. Text / Annotation Changes
Text content sets are diffed to surface added/removed room labels, notes, and revision marks.

### 5. Dimension Changes
Dimension annotation count changes flag drawing re-dimensioning or annotation removal.

---

## Output Structure

Each detected change is returned as a structured object:

```json
{
  "category": "SYMBOL",
  "severity": "high",
  "type": "removed",
  "icon": "🚪",
  "change": "Removed 2 doors",
  "detail": "Block 'DOOR-FD30': 3 → 2"
}
```

The full response includes:

- `totalChanges`, `added`, `removed` counts
- `overall` classification: `identical` / `minor differences` / `moderate changes` / `significant changes`
- Per-category grouped change list
- Stats: entity totals, layer counts for both files

---

## AI Narrative Layer (Optional)

After the algorithmic diff, users can optionally request a natural-language summary. The structured change list is sent to either:

- **Claude (Anthropic)** — proxied through Spring Boot (`/api/ai/messages/stream`) with the API key held server-side. Uses SSE streaming.
- **OpenAI GPT-4o** — called directly from the browser using the user's own API key. Uses OpenAI's SSE streaming format.

The prompt instructs the model to act as an AEC document reviewer and interpret the changes in construction/engineering terms rather than repeating the raw entity list.

**Cost:** ~$0.003 per summary (Claude Sonnet), ~$0.005 (GPT-4o).

---

## Technology Stack

| Component | Technology | Version | License |
|---|---|---|---|
| DXF parsing & rendering | **ezdxf** | 1.4.4 | MIT |
| DWG → DXF conversion | **ODA File Converter** | 27.x | Free (non-commercial) |
| HTTP microservice | Python stdlib `http.server` | 3.10+ | — |
| API proxy | Spring Boot `java.net.http` | Java 21 | — |
| AI summary (Claude) | Anthropic API | claude-sonnet-4 | Pay-per-use |
| AI summary (OpenAI) | OpenAI API | gpt-4o | Pay-per-use |

---

## Supported File Types

| Format | Comparison Method |
|---|---|
| `.dxf` | Direct ezdxf parse → entity diff |
| `.dwg` (all versions R14–2023) | ODA CLI conversion → DXF → entity diff |
| `.ifc` | ifcopenshell element count diff by type + storey |
| `.pdf` / Office | LibreOffice text extraction → line-level diff |
| Images | NumPy/Pillow pixel diff with % change |

---

## Performance Characteristics

| File Size | DXF Parse Time | DWG Conversion (ODA) | Total Response |
|---|---|---|---|
| Small (< 500 KB) | ~0.2s | ~3–5s | ~5s |
| Medium (500 KB–5 MB) | ~0.5s | ~8–15s | ~15s |
| Large (> 5 MB) | ~1–3s | ~20–40s | ~40s |

ODA conversion is the dominant cost for DWG files. DXF files are significantly faster (no conversion step).

---

## Limitations & Future Enhancements

| Current Limitation | Potential Enhancement |
|---|---|
| Block name matching uses keyword heuristics | Load block library manifest from project metadata |
| No geometric position diff (only counts) | Spatial bounding-box comparison for moved elements |
| ODA required for DWG (free, but manual install) | Evaluate LibreDWG as zero-install fallback |
| Text diff is exact-match set comparison | Fuzzy/semantic matching for revised annotations |
| No visual overlay of changes | SVG diff overlay highlighting changed regions |

---

## Security & Deployment Notes

- The ODA File Converter and Python microservice run **locally on the application server** — no drawing data leaves the network
- API keys (Anthropic) are held server-side in `application.yml` or injected via environment variable — never exposed to the browser
- OpenAI calls are made client-side using the user's own key (optional feature, user opt-in)
- The comparison endpoint (`/api/compare`) requires JWT authentication — unauthenticated requests are rejected

---

*Generated from live source: `converter/app.py` and `CompareController.java`*
