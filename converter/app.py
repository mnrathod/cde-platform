#!/usr/bin/env python3
"""
CDE Converter microservice — port 5001
Cross-platform: Windows + Ubuntu/Linux/Mac
"""

import os, sys, io, json, shutil, tempfile, subprocess, traceback, time, platform
import re, html, base64, struct
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse
from pathlib import Path

PORT = int(os.environ.get("CONVERTER_PORT", 5001))

# ══════════════════════════════════════════════════════════════
#  What this module keeps, and what it hands to its neighbours
# ══════════════════════════════════════════════════════════════
# app.py is the HTTP surface and the routing between formats. The work
# itself lives in modules beside it, each under the §3.3 size limit that a
# 3,600-line app.py had long stopped meeting.
#
# Every name is re-exported here rather than left namespaced, because the
# request handler and the whole test suite reach these off this module.
# That is a deliberate compatibility seam, not an accident: the import is
# what keeps `app.render_dxf_string` meaning what it has always meant.
from file_types import DWG_VERSIONS, OFFICE_EXTS  # noqa: E402,F401
from toolchain import (  # noqa: E402,F401
    IS_WINDOWS, ODA_BINARY_NAME, DWG_REMEDY,
    make_temp_dir, safe_rmtree, run_cmd,
    find_libreoffice, libreoffice_to_pdf, find_tesseract,
)
from dwg_oda import (  # noqa: E402,F401
    _oda_candidate, find_oda, oda_launch_prefix,
    probe_oda, oda_status, dwg_via_oda,
)
from dxf_text_layer import (  # noqa: E402,F401
    TEXT_LAYER_CLASS, mtext_string, _rendered_extent, _text_entities,
    calibrate_dxf_to_svg, _text_elements, build_text_layer,
)
from dxf_render import (  # noqa: E402,F401
    EZDXF_OK, EZDXF_ERR, ezdxf,
    PRINT_PAGE_MM, PRINT_BACKGROUND,
    RenderContext, Frontend, SVGBackend, LayoutProperties,
    render_dxf, render_dxf_string, read_dxf_document,
    first_populated_layout, _render_dxf_file,
    _print_svg, _geometry_of, dxf_file_to_pdf, dxf_string_to_pdf,
)
from cad_comparison import (  # noqa: E402,F401
    _load_dxf_for_compare, _load_dxf_from_path_via_oda,
    _analyze_dxf, _compare_cad, _compare_ifc,
)
from document_comparison import _extract_text, _compare_documents  # noqa: E402,F401
from image_comparison import _compare_images, _compare_metadata  # noqa: E402,F401


def is_dwg(path: str) -> tuple:
    try:
        with open(path, "rb") as f:
            magic = f.read(6).decode("ascii", errors="replace")
        if magic.startswith("AC"):
            return True, DWG_VERSIONS.get(magic[:6], magic[:6])
    except Exception:
        pass
    return False, ""


# ══════════════════════════════════════════════════════════════
#  Main dispatcher
# ══════════════════════════════════════════════════════════════
def is_revit(path: str) -> bool:
    """Detect Revit .rvt/.rfa files by OLE2 magic bytes."""
    try:
        with open(path, "rb") as f:
            magic = f.read(8)
        return magic[:4] == bytes([0xD0, 0xCF, 0x11, 0xE0])
    except Exception:
        return False


def extract_ifc_geometry(ifc_path: str) -> dict:
    """
    Pull renderable geometry out of an IFC file, grouped by element type.

    Returns either {"success": False, "error": ...} or a dict holding the
    numpy arrays and the group table. Serialising it is somebody else's job —
    `ifc_geometry_json` and `ifc_geometry_binary` are the two callers, and
    keeping the extraction free of either encoding is what lets the binary
    one exist without duplicating any of this.

    Geometry is accumulated **per IFC type rather than in iterator order**, so
    that each type ends up as one contiguous run of indices. That run is what
    a renderer needs to draw the type with its own material.

    This replaced a per-vertex colour attribute. Every vertex used to carry a
    float32x3 copy of its element type's colour — twelve bytes each, to encode
    one of sixteen values that are a property of the type, not of the vertex.
    On a million-vertex model that is 12 MB of tiling, and it also fused the
    whole building into a single material, which is why nothing could hide a
    layer.
    """
    try:
        import ifcopenshell
        import ifcopenshell.geom
        import numpy as np
    except ImportError as e:
        return {"success": False, "error": f"ifcopenshell not installed: {e}\nRun: pip install ifcopenshell"}

    try:
        ifc = ifcopenshell.open(ifc_path)
        print(f"[IFC] Opened: schema={ifc.schema}, products={len(ifc.by_type('IfcProduct'))}", flush=True)
    except Exception as e:
        return {"success": False, "error": f"Cannot open IFC: {e}"}

    # Colour map by IFC type
    TYPE_COLORS = {
        "IfcWall":          [0.85, 0.82, 0.78, 1.0],
        "IfcWallStandardCase": [0.85, 0.82, 0.78, 1.0],
        "IfcSlab":          [0.75, 0.75, 0.75, 1.0],
        "IfcRoof":          [0.62, 0.45, 0.35, 1.0],
        "IfcColumn":        [0.80, 0.75, 0.70, 1.0],
        "IfcBeam":          [0.70, 0.65, 0.60, 1.0],
        "IfcDoor":          [0.65, 0.45, 0.25, 1.0],
        "IfcWindow":        [0.55, 0.75, 0.90, 0.5],
        "IfcStair":         [0.80, 0.78, 0.75, 1.0],
        "IfcRamp":          [0.78, 0.76, 0.72, 1.0],
        "IfcFurnishingElement": [0.60, 0.50, 0.40, 1.0],
        "IfcFlowTerminal":  [0.40, 0.65, 0.80, 1.0],
        "IfcFlowSegment":   [0.70, 0.70, 0.30, 1.0],
        "IfcPlate":         [0.75, 0.73, 0.70, 1.0],
        "IfcMember":        [0.65, 0.60, 0.55, 1.0],
    }
    DEFAULT_COLOR = [0.70, 0.68, 0.65, 1.0]

    settings = ifcopenshell.geom.settings()
    settings.set(settings.USE_WORLD_COORDS, True)
    settings.set(settings.WELD_VERTICES, True)

    # type -> its own arrays, so each type can be emitted as one run.
    buckets    = {}
    mesh_count = 0

    try:
        # Filter to only element types that have geometry
        include_types = [
            'IfcWall','IfcWallStandardCase','IfcSlab','IfcRoof','IfcColumn',
            'IfcBeam','IfcDoor','IfcWindow','IfcStair','IfcRamp',
            'IfcFurnishingElement','IfcPlate','IfcMember','IfcCovering',
            'IfcFlowTerminal','IfcFlowSegment','IfcOpeningElement',
        ]
        # Try with type filter first, fall back to all types
        it = None
        for attempt in ['filtered', 'all']:
            try:
                if attempt == 'filtered':
                    it = ifcopenshell.geom.iterator(settings, ifc, include_entities=include_types)
                else:
                    it = ifcopenshell.geom.iterator(settings, ifc)
                if it.initialize():
                    print(f"[IFC] Iterator initialized ({attempt})", flush=True)
                    break
                else:
                    it = None
            except Exception as e:
                print(f"[IFC] Iterator ({attempt}) error: {e}", flush=True)
                it = None

        if it is None:
            prod_count = len(ifc.by_type('IfcProduct'))
            return {"success": False, "error":
                f"IFC file has no renderable geometry ({prod_count} products found). "
                f"The file may contain only 2D data or have no geometric representations."}

        while True:
            shape = it.get()
            geo   = shape.geometry
            verts  = np.array(geo.verts,  dtype=np.float32).reshape(-1, 3)
            norms  = np.array(geo.normals,dtype=np.float32).reshape(-1, 3) if geo.normals else np.zeros_like(verts)
            faces  = np.array(geo.faces,  dtype=np.uint32).reshape(-1, 3)

            if len(verts) == 0 or len(faces) == 0:
                if not it.next(): break
                continue

            # Faces are offset within the bucket here and shifted again to
            # global positions at assembly, because a bucket's final place in
            # the vertex array is not known until every element has been read.
            bucket = buckets.setdefault(shape.type, {
                "positions": [], "normals": [], "faces": [],
                "vertexCount": 0, "elementCount": 0,
            })
            bucket["positions"].append(verts)
            bucket["normals"].append(norms)
            bucket["faces"].append(faces + bucket["vertexCount"])
            bucket["vertexCount"] += len(verts)
            # How many elements of this type the model holds, counted rather
            # than guessed. The viewer's model tree used to invent this with
            # Math.random() when no hierarchy endpoint answered, so a reader
            # was shown fabricated quantities for their own building.
            bucket["elementCount"] += 1
            mesh_count += 1

            if not it.next():
                break

    except Exception as e:
        return {"success": False, "error": f"Geometry extraction failed: {e}"}

    if mesh_count == 0:
        return {"success": False, "error": "No geometry found in IFC file. "
                "Ensure the file contains IfcWall, IfcSlab or other building elements with geometry."}

    assembled = assemble_geometry_buckets(buckets, TYPE_COLORS, DEFAULT_COLOR)

    print(f"[IFC] Total: {assembled['vertexCount']} verts, "
          f"{assembled['triangleCount']} triangles, {mesh_count} elements, "
          f"{len(assembled['groups'])} groups", flush=True)

    assembled["elementCount"] = int(mesh_count)
    assembled["schema"] = ifc.schema
    return assembled


def assemble_geometry_buckets(buckets: dict, type_colors: dict, default_color: list) -> dict:
    """
    Flatten per-type buckets into one set of buffers plus a group table.

    Each type becomes one contiguous run of indices, and one group naming it.
    Two offsets are in play and they are not interchangeable:

      * Faces arrive numbered relative to their own bucket, so each run is
        shifted by the number of vertices already emitted before it. Miss this
        and every type after the first indexes into the wrong vertices.
      * A group's `start`/`count` are in INDEX units — what three.js
        `addGroup` wants on indexed geometry. Vertex units there would draw
        the wrong elements rather than raising anything.

    Separate from the extraction above so it can be tested without an IFC file
    or ifcopenshell: this arithmetic is where the silent-corruption bugs live.
    """
    import numpy as np

    positions     = []
    normals       = []
    indices       = []
    groups        = []
    vertex_offset = 0
    index_offset  = 0

    for ifc_type, bucket in sorted(buckets.items()):
        type_faces = np.concatenate(bucket["faces"], axis=0) + vertex_offset
        positions.append(np.concatenate(bucket["positions"], axis=0))
        normals.append(np.concatenate(bucket["normals"], axis=0))
        indices.append(type_faces)

        colour = type_colors.get(ifc_type, default_color)
        groups.append({
            "type":    ifc_type,
            "start":   int(index_offset),
            "count":   int(type_faces.size),
            # Elements of this type, not triangles. `count` above is an index
            # count and the two are nothing like each other — a single wall is
            # hundreds of indices — so they are named apart deliberately.
            "elementCount": int(bucket.get("elementCount", 0)),
            "color":   [float(channel) for channel in colour[:3]],
            # The colour table always carried an alpha and `col[:3]` always
            # dropped it, so a window rendered as solid as a wall. One
            # material per type is what makes honouring it possible.
            "opacity": float(colour[3]) if len(colour) > 3 else 1.0,
        })
        vertex_offset += bucket["vertexCount"]
        index_offset  += type_faces.size

    positions = np.concatenate(positions, axis=0).astype("<f4")
    normals   = np.concatenate(normals,   axis=0).astype("<f4")
    indices   = np.concatenate(indices,   axis=0).astype("<u4")

    return {
        "success":       True,
        "type":          "ifc3d",
        "positions":     positions,
        "normals":       normals,
        "indices":       indices,
        "groups":        groups,
        "vertexCount":   int(len(positions)),
        "triangleCount": int(len(indices)),
        "bounds": {
            "min": positions.min(axis=0).tolist(),
            "max": positions.max(axis=0).tolist(),
        },
    }


# Header of the binary geometry container, in order. Little-endian throughout,
# which `<f4`/`<u4`/`<I` state explicitly rather than inheriting from whatever
# the host happens to be — a format that only works on x86 is not a format.
GEOMETRY_MAGIC   = b"CDEG"
GEOMETRY_VERSION = 1


def _geometry_meta(geometry: dict) -> dict:
    """The parts of an extraction that describe it, without the buffers."""
    return {key: value for key, value in geometry.items()
            if key not in ("positions", "normals", "indices")}


def ifc_geometry_json(ifc_path: str) -> dict:
    """
    The extraction as JSON with base64 buffers.

    Kept for `GET /api/viewer3d/{documentId}`, whose media type is part of a
    published contract (§3.4) even though the geometry inside it is documented
    as opaque. New clients use the binary container instead: base64 is four
    bytes of transfer for every three of payload, which on a real model is
    tens of megabytes spent encoding numbers that were already bytes.
    """
    geometry = extract_ifc_geometry(ifc_path)
    if not geometry.get("success"):
        return geometry

    def to_b64(array):
        return base64.b64encode(array.tobytes()).decode()

    payload = _geometry_meta(geometry)
    payload.pop("success", None)
    payload.pop("type", None)
    payload.update({
        "positions": to_b64(geometry["positions"]),
        "normals":   to_b64(geometry["normals"]),
        "indices":   to_b64(geometry["indices"]),
    })
    return {"success": True, "type": "ifc3d", "gltfData": payload}


def encode_geometry_container(geometry: dict) -> bytes:
    """
    The extraction as a self-describing binary blob.

    Layout, all little-endian:

        magic        4   b"CDEG"
        version      4   uint32
        headerLength 4   uint32
        header       n   UTF-8 JSON — counts, schema, bounds, groups
        padding      0-3 zero bytes, to a 4-byte boundary
        positions        float32 x 3 x vertexCount
        normals          float32 x 3 x vertexCount
        indices          uint32  x 3 x triangleCount

    The padding is not decoration. A reader takes typed-array views directly
    over the received bytes, and both `Float32Array` and `Uint32Array` refuse
    a byteOffset that is not a multiple of four — so a header of the wrong
    length would throw rather than merely being untidy.

    Takes an extraction rather than a path, so it can be exercised on
    synthetic geometry without an IFC file and without ifcopenshell present.
    """
    header = json.dumps(_geometry_meta(geometry), separators=(",", ":")).encode("utf-8")
    padding = (-len(header)) % 4

    return b"".join([
        GEOMETRY_MAGIC,
        struct.pack("<I", GEOMETRY_VERSION),
        struct.pack("<I", len(header)),
        header,
        b"\0" * padding,
        geometry["positions"].tobytes(),
        geometry["normals"].tobytes(),
        geometry["indices"].tobytes(),
    ])


def ifc_geometry_binary(ifc_path: str):
    """
    Extract one IFC file and encode it.

    Returns `(bytes, None)` on success or `(None, error_dict)` on failure, so
    the caller can send the error as JSON rather than as a broken model.
    """
    geometry = extract_ifc_geometry(ifc_path)
    if not geometry.get("success"):
        return None, geometry
    return encode_geometry_container(geometry), None


def convert(file_path: str, content_type: str = "", target_format: str = "") -> dict:
    """
    Convert one file for one purpose.

    {@code target_format} says what the caller will do with the result, which
    for CAD decides the whole output: the viewer wants SVG it can put in a
    canvas and mark up, and an export wants PDF. Passing "PDF" asks for a file
    rather than something to render, and a format with no PDF form is refused
    with a reason rather than answered with a substitute.
    """
    # Resolve to absolute, normalise slashes
    try:
        abs_path = str(Path(file_path).resolve())
    except Exception as e:
        return {"success": False, "error": f"Invalid path: {file_path} — {e}"}

    wants_pdf = target_format.upper() == "PDF"
    print(f"[CONVERT] path={abs_path} ct={content_type} target={target_format or 'viewer'}",
          flush=True)

    if not os.path.exists(abs_path):
        return {"success": False,
                "error": f"File not found: {abs_path}\n"
                         f"(received from Java: {file_path})"}

    ext = Path(abs_path).suffix.lower().lstrip(".")
    ct  = content_type.lower()

    # Office -> PDF
    if ext in OFFICE_EXTS or any(x in ct for x in [
            "word","excel","powerpoint","opendocument","rtf","text/plain","text/csv"]):
        r = libreoffice_to_pdf(abs_path)
        if r["success"]:
            return {"success": True, "type": "pdf", "pdfBytes": r["pdfBytes"]}
        return {"success": False, "error": r["error"], "type": "office_error",
                "loInstalled": find_libreoffice() is not None}

    # PDF passthrough
    if ext == "pdf" or "pdf" in ct:
        return {"success": True, "type": "pdf",
                "pdfBytes": Path(abs_path).read_bytes()}

    # DWG -> DXF -> SVG for the viewer, or -> PDF for an export
    dwg, version = is_dwg(abs_path)
    if dwg or ext == "dwg":
        render = dxf_string_to_pdf if wants_pdf else render_dxf_string

        r = dwg_via_oda(abs_path, render)
        if r.get("success"):
            r["convertedBy"] = "ODA"; r["dwgVersion"] = version; return r

        # ODA is the only DWG route. There was a LibreDWG fallback here and
        # it is gone with the binary — see ADR 13: shipping a GPL-3.0
        # executable inside a product customers install triggers §6, and the
        # obligation travels with every copy.
        #
        # This payload reaches the person whose drawing would not open, so it
        # answers their question — can this be converted here, and if not what
        # do I do — rather than ours. `odaInstalled` reports *runnable*: a
        # binary that is mounted and cannot start is not available, and
        # reporting it as installed sends them looking for a fault in the
        # drawing.
        oda = oda_status()
        return {"success": False, "error": "DWG_NEED_CONVERTER",
                "dwgVersion": version,
                "odaInstalled": oda["runnable"],
                "odaDetail": oda["detail"],
                "remedy": DWG_REMEDY,
                "odaError": r.get("error", "")}

    # DXF -> SVG for the viewer, or -> PDF for an export
    if ext == "dxf" or "dxf" in ct:
        return dxf_file_to_pdf(abs_path) if wants_pdf else render_dxf(abs_path)

    # 3D formats — also detect by content type
    if ext == "ifc" or "ifc" in ct or "step" in ct:
        if wants_pdf:
            # Said plainly rather than answered with a picture of a viewport:
            # a model has no page, no scale and no sheet, so any PDF of one is
            # a choice this service is not entitled to make on the caller's
            # behalf. The model tree endpoint is the useful answer.
            return {"success": False,
                    "error": "A 3D model has no defined PDF form. Use the model "
                             "tree endpoint to read its structure, or export a "
                             "drawing sheet from the authoring tool first."}
        print(f"[IFC] Routing to ifc_geometry_json: {abs_path}", flush=True)
        return ifc_geometry_json(abs_path)

    if ext in ("rvt", "rfa"):
        return {
            "success": False, "error": "REVIT_BINARY",
            "type": "revit_binary",
            "fileName": os.path.basename(abs_path)
        }

    if ext in ("glb", "gltf", "obj", "stl", "ply", "dae", "3ds"):
        if wants_pdf:
            return {"success": False,
                    "error": "A 3D model has no defined PDF form. Use the model "
                             "tree endpoint to read its structure, or export a "
                             "drawing sheet from the authoring tool first."}
        return {
            "success": True, "type": "model3d_passthrough",
            "ext": ext,
            "filePath": abs_path
        }

    return {"success": False, "error": f"Unsupported type: .{ext}"}


# ══════════════════════════════════════════════════════════════
#  HTTP Server
# ══════════════════════════════════════════════════════════════
class Handler(BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        if args and str(args[1]) not in ("200", "204"):
            super().log_message(fmt, *args)

    def do_GET(self):
        if urlparse(self.path).path == "/health":
            lo   = find_libreoffice()
            tess = find_tesseract()
            oda  = oda_status()
            self._json(200, {
                "status": "ok", "platform": platform.system(),
                "ezdxf": EZDXF_OK,
                "ezdxfVersion": ezdxf.__version__ if EZDXF_OK else None,
                "libreoffice": lo is not None, "libreofficePath": lo,
                # Both fields, because they answer different questions and the
                # gap between them is where a mounted ODA goes wrong: installed
                # says a binary was found, runnable says it started.
                "odaInstalled": oda["installed"], "odaPath": oda["path"],
                "odaRunnable": oda["runnable"], "odaDetail": oda["detail"],
                "tesseractInstalled": tess is not None, "tesseractPath": tess,
            })
        else:
            self._json(404, {"error": "Use POST /convert"})

    def do_POST(self):
        ppath  = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", 0))
        raw    = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw) if raw else {}
        except Exception:
            self._json(400, {"success": False, "error": "Invalid JSON"}); return

        if ppath == "/redact":
            result = redact_pdf(
                body.get("path",""),
                body.get("regions", []),
                body.get("output",""),
                body.get("burn", True),
                body.get("restoreTextLayer", True)
            )
            self._json(200, result)
            return

        if ppath == "/ocr":
            result = ocr_pdf_to_searchable(
                body.get("path",""),
                body.get("output",""),
                body.get("lang","eng"),
                body.get("dpi",300),
                body.get("skipTextPages", True)
            )
            self._json(200, result)
            return

        if ppath == "/flatten":
            result = flatten_annotations_to_pdf(
                body.get("path",""),
                body.get("shapes", []),
                body.get("output",""),
                body.get("quality","screen")
            )
            self._json(200, result)
            return

        if ppath == "/form-fields":
            result = inspect_pdf_form(body.get("path",""))
            self._json(200, result)
            return

        if ppath == "/form-fill":
            result = fill_pdf_form(
                body.get("path",""),
                body.get("fields", {}),
                body.get("output",""),
                body.get("flatten", False)
            )
            self._json(200, result)
            return

        if ppath == "/find-text":
            result = find_text_matches(
                body.get("path",""),
                body.get("terms", []),
                body.get("regexes", []),
                body.get("presets", []),
                body.get("matchCase", False),
                body.get("wholeWord", False)
            )
            self._json(200, result)
            return

        if ppath == "/page-info":
            self._json(200, describe_pdf_pages(body.get("path","")))
            return

        if ppath == "/rearrange-pages":
            result = rearrange_pdf_pages(
                body.get("path",""),
                body.get("plan", []),
                body.get("output",""),
                body.get("sources", {})
            )
            self._json(200, result)
            return

        if ppath == "/ifc-tree":
            result = extract_ifc_tree(body.get("path",""))
            self._json(200, result)
            return

        if ppath == "/ifc-geometry":
            # Binary on success, JSON on failure — the caller tells them apart
            # by Content-Type, so a failed extraction never arrives looking
            # like a model with a very short header.
            blob, error = ifc_geometry_binary(body.get("path", "").strip())
            if error is not None:
                self._json(200, error)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(blob)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(blob)
            return

        if ppath == "/compare":
            path1 = body.get("path1","").strip()
            path2 = body.get("path2","").strip()
            ct1   = body.get("contentType1","")
            ct2   = body.get("contentType2","")
            if not path1 or not path2:
                self._json(400, {"success": False, "error": "Missing path1 or path2"}); return
            self._json(200, compare_files(path1, path2, ct1, ct2))
            return

        if ppath != "/convert":
            self._json(404, {"error": "Not found"}); return

        file_path    = body.get("path", "").strip()
        content_type = body.get("contentType", "")
        target       = body.get("targetFormat", "")

        if not file_path:
            self._json(400, {"success": False, "error": "Missing 'path'"}); return

        result = convert(file_path, content_type, target)

        rtype = result.get("type", "")

        # PDF — send as binary
        if result.get("success") and rtype == "pdf":
            pdf_bytes = result["pdfBytes"]
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(pdf_bytes)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Disposition", "inline")
            self.end_headers()
            self.wfile.write(pdf_bytes)

        # 3D model passthrough (GLB/OBJ/STL) — serve raw bytes
        elif result.get("success") and rtype == "model3d_passthrough":
            ext = result.get("ext","glb")
            mime_map = {"glb":"model/gltf-binary","gltf":"model/gltf+json",
                        "obj":"text/plain","stl":"application/octet-stream",
                        "ply":"application/octet-stream","dae":"text/xml"}
            mime = mime_map.get(ext, "application/octet-stream")
            raw = Path(result["filePath"]).read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(raw)

        # IFC 3D data — JSON with base64 geometry
        elif result.get("success") and rtype == "ifc3d":
            result.pop("pdfBytes", None)
            self._json(200, result)

        else:
            result.pop("pdfBytes", None)
            self._json(200, result)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def _json(self, status, data):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)



# ══════════════════════════════════════════════════════════════
#  3D VIEWER — IFC / glTF / OBJ / STL
# ══════════════════════════════════════════════════════════════

MODEL_3D_EXTS = {"ifc", "glb", "gltf", "obj", "stl", "ply", "dae", "3ds"}



# ══════════════════════════════════════════════════════════════
#  FILE COMPARISON ENGINE
# ══════════════════════════════════════════════════════════════

def compare_files(path1: str, path2: str, ct1: str = "", ct2: str = "") -> dict:
    """
    Compare two files and return structured change report.
    Supports: DXF/DWG, IFC, PDF/Office (text), images
    """
    p1 = Path(path1); p2 = Path(path2)
    ext1 = p1.suffix.lower().lstrip('.')
    ext2 = p2.suffix.lower().lstrip('.')

    print(f"[COMPARE] {p1.name} vs {p2.name}", flush=True)

    # Route by file type
    if ext1 in ('dxf', 'dwg') and ext2 in ('dxf', 'dwg'):
        return _compare_cad(path1, path2, ext1, ext2)
    elif ext1 == 'ifc' and ext2 == 'ifc':
        return _compare_ifc(path1, path2)
    elif ext1 in OFFICE_EXTS | {'pdf'} and ext2 in OFFICE_EXTS | {'pdf'}:
        return _compare_documents(path1, path2, ext1, ext2)
    elif ext1 in ('png','jpg','jpeg','bmp','gif') and ext2 in ('png','jpg','jpeg','bmp','gif'):
        return _compare_images(path1, path2)
    else:
        # Mixed types — do basic metadata comparison
        return _compare_metadata(path1, path2)


# ══════════════════════════════════════════════════════════════════
#  PDF REDACTION
# ══════════════════════════════════════════════════════════════════

# Render DPI for pages that get rasterized. Must be passed to BOTH the
# render call and the save call — see _rewrite_pdf_pages.
REDACTION_DPI = 150
FLATTEN_DPI   = {"screen": 150, "print": 300}


def redact_pdf(path: str, regions: list, output: str = "", burn: bool = True,
               restore_text_layer: bool = True) -> dict:
    """
    Redact rectangular regions from a PDF.
    burn=True  → permanently destroy content (production)
    burn=False → add black rectangle overlay only (preview)

    regions: [{page, x, y, width, height, reason}]
    Coordinates are in PDF points (72 pt = 1 inch).
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}
    if not regions:
        return {"success": False, "error": "No redaction regions provided"}

    try:
        import pypdfium2 as pdfium

        # Drop regions pointing outside the document rather than failing the
        # whole request over one bad index.
        doc = pdfium.PdfDocument(path)
        page_count = len(doc)
        doc.close()

        by_page = {
            idx: items
            for idx, items in _group_by_page(regions).items()
            if 0 <= idx < page_count
        }
        if not by_page:
            return {"success": False,
                    "error": f"No regions fall within this document's {page_count} page(s)"}

        if not output:
            base = os.path.splitext(path)[0]
            output = base + ("_redacted.pdf" if burn else "_redact_preview.pdf")

        redacted_pages = 0
        if burn:
            redacted_pages = _rewrite_pdf_pages(
                path, output, REDACTION_DPI, by_page, _draw_redactions,
                drop_annots_in=_redaction_rects,
                restore_text_layer=restore_text_layer
            )
        # Preview mode draws the overlay client-side; nothing to write here.

        return {
            "success": True,
            "outputPath": output,
            "redactedPages": redacted_pages,
            "totalPages": page_count,
            "totalRegions": len(regions),
            "mode": "permanent" if burn else "preview"
        }

    except ImportError as e:
        return {"success": False, "error": f"Required library missing: {e}. Run: pip install pypdfium2 pypdf Pillow"}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ── Finding text to redact ───────────────────────────────────────

# Patterns for the categories people actually need removed before a document
# leaves the organisation. Deliberately conservative: a pattern that
# over-matches causes silent loss of legitimate content, and redaction cannot
# be undone within the file.
REDACTION_PATTERNS = {
    "email":      r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}",
    # Credit cards, allowing space or hyphen grouping.
    "creditCard": r"\b(?:\d[ -]?){13,19}\b",
    "ssn":        r"\b\d{3}-\d{2}-\d{4}\b",
    # UK National Insurance number.
    "niNumber":   r"\b[A-CEGHJ-PR-TW-Z]{2}\s?\d{2}\s?\d{2}\s?\d{2}\s?[A-D]\b",
    # UK postcode.
    "postcode":   r"\b[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}\b",
    "iban":       r"\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b",
    # Phone numbers require an international prefix or a leading-zero trunk
    # code, and are anchored so they cannot start or end mid-digit-run. A
    # looser pattern matched card numbers, IBAN tails and NI digits — and
    # since redaction is irreversible, a preset that destroys the wrong
    # content is worse than one that occasionally misses.
    "phone":      r"(?<![\d+])(?:\+\d{1,3}[ -]\d{2,5}|\(?0\d{2,4}\)?)[ -]?\d{3,4}[ -]?\d{3,4}(?![\d])",
}

# Redaction boxes are padded so glyph edges and descenders are covered; a box
# fitted exactly to the reported extents can leave a readable sliver.
_MATCH_PADDING_PT = 1.0


def _compile_search_patterns(terms=None, regexes=None, presets=None,
                             match_case: bool = False,
                             whole_word: bool = False) -> tuple:
    """
    Turn every kind of request into one list of compiled patterns.

    Literal terms, caller-supplied regexes and named presets all end up as
    regexes, so the matching loop has a single path rather than three that
    can disagree about case handling or word boundaries.

    Returns (patterns, error) — error is a message when a request is unusable.
    """
    import re

    flags = 0 if match_case else re.IGNORECASE
    compiled = []

    for term in (terms or []):
        if not str(term).strip():
            continue
        body = re.escape(str(term))
        if whole_word:
            body = r"\b" + body + r"\b"
        compiled.append((f"term:{term}", re.compile(body, flags)))

    for name in (presets or []):
        pattern = REDACTION_PATTERNS.get(name)
        if not pattern:
            return [], f"Unknown pattern '{name}'. Available: {', '.join(sorted(REDACTION_PATTERNS))}"
        compiled.append((f"preset:{name}", re.compile(pattern, flags)))

    for expression in (regexes or []):
        try:
            compiled.append((f"regex:{expression}", re.compile(expression, flags)))
        except re.error as e:
            return [], f"Invalid regular expression '{expression}': {e}"

    if not compiled:
        return [], "Nothing to search for — supply a term, a pattern or an expression."
    return compiled, None


def _match_rects(textpage, start: int, end: int) -> list:
    """
    Rectangles covering characters [start, end) of a page's text.

    A match that wraps across a line needs one rectangle per line: a single
    box spanning both would black out everything between them, including
    content nobody asked to remove. Runs are split where the text drops to a
    new baseline.
    """
    rects, run = [], []

    def flush():
        if not run:
            return
        left   = min(box[0] for box in run)
        bottom = min(box[1] for box in run)
        right  = max(box[2] for box in run)
        top    = max(box[3] for box in run)
        rects.append((left, bottom, right, top))
        run.clear()

    previous = None
    for index in range(start, end):
        try:
            box = textpage.get_charbox(index)
        except Exception:
            continue
        left, bottom, right, top = box
        # Newlines and other zero-area characters carry no position.
        if right <= left or top <= bottom:
            continue

        # Split on vertical overlap rather than on baseline distance.
        # Glyphs on one line sit at visibly different heights — a '+' against
        # a digit, a descender against an x-height letter — so comparing
        # bottoms split single lines into several boxes and reported one
        # match as three.
        if previous is not None:
            overlap = min(previous[3], top) - max(previous[1], bottom)
            if overlap <= 0:
                flush()
        run.append(box)
        previous = box

    flush()
    return rects


def find_text_matches(path: str, terms=None, regexes=None, presets=None,
                      match_case: bool = False, whole_word: bool = False) -> dict:
    """
    Locate text in a PDF and report where it sits.

    Coordinates come back in PDF points with a bottom-left origin — the same
    space {@code redact_pdf} takes — so a search result can be handed straight
    to redaction without conversion, and the client can preview exactly what
    would be destroyed before it happens.

    Pages with no text layer yield nothing, which is worth saying out loud:
    searching a scan finds nothing until it has been through OCR.
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    patterns, error = _compile_search_patterns(
        terms, regexes, presets, match_case, whole_word)
    if error:
        return {"success": False, "error": error}

    try:
        import pypdfium2 as pdfium

        document = pdfium.PdfDocument(path)
        matches, pages_without_text = [], 0

        try:
            for index in range(len(document)):
                page = document[index]
                textpage = page.get_textpage()
                try:
                    text = textpage.get_text_range() or ""
                    if not text.strip():
                        pages_without_text += 1
                        continue

                    height = page.get_height()
                    for label, pattern in patterns:
                        for found in pattern.finditer(text):
                            for left, bottom, right, top in _match_rects(
                                    textpage, found.start(), found.end()):
                                matches.append({
                                    "page":    index + 1,
                                    "text":    found.group(0),
                                    "pattern": label,
                                    "x":       round(left - _MATCH_PADDING_PT, 2),
                                    "y":       round(bottom - _MATCH_PADDING_PT, 2),
                                    "width":   round(right - left + _MATCH_PADDING_PT * 2, 2),
                                    "height":  round(top - bottom + _MATCH_PADDING_PT * 2, 2),
                                    "pageHeight": round(height, 2),
                                })
                finally:
                    textpage.close()
        finally:
            document.close()

        return {
            "success":          True,
            "matchCount":       len(matches),
            "matches":          matches,
            "pagesWithoutText": pages_without_text,
        }

    except ImportError as e:
        return {"success": False, "error": f"Required library missing: {e}"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def _group_by_page(items: list, key: str = "page") -> dict:
    """Group edit items by 0-based page index."""
    grouped = {}
    for item in items:
        idx = int(item.get(key, 1)) - 1
        grouped.setdefault(idx, []).append(item)
    return grouped


def _rewrite_pdf_pages(path: str, output: str, dpi: int,
                       edits_by_page: dict, draw_page,
                       drop_annots_in=None, restore_text_layer: bool = False) -> int:
    """
    Rebuild a PDF, rasterizing ONLY the pages that carry edits and leaving
    every other page untouched.

    Leaving untouched pages alone matters for correctness, not just speed:
    rasterizing a page discards its vector content and its text layer, so
    blanket-rasterizing silently makes an ENTIRE document unsearchable
    because one region on one page was redacted.

    The document is cloned and the affected pages have their content
    *replaced* in place, rather than being rebuilt page by page into an empty
    writer. Rebuilding dropped the document catalog — most visibly /AcroForm,
    which meant redacting or flattening any page destroyed the form fields on
    every page, and a later form-fill reported the file as having no form at
    all. Cloning keeps the catalog, the page objects and their annotations;
    only the page's own drawing content is swapped for the raster.

    The render DPI and the DPI declared when saving must be the same number,
    or every rasterized page is rescaled by their ratio — rendering at
    scale=2 (144dpi) while declaring resolution=150 shrank every page to
    96% of its original size.

    draw_page(draw, items, scale_x, scale_y, page_width, page_height)
    performs the actual drawing.

    drop_annots_in(items, page_width, page_height) -> list of rectangles whose
    annotations must not survive. Redaction supplies this: a widget sitting
    over redacted content would still carry its value in the file even though
    the pixels beneath it are gone.

    restore_text_layer re-runs OCR over any page that had extractable text
    before it was rasterized. Rasterizing destroys the whole page's text, so
    redacting one email address from a report made the entire page
    unsearchable — a document you searched to find something became unusable
    by removing it. The redacted areas are solid black, so nothing sensitive
    is recovered.

    Returns the count of rasterized pages.
    """
    import pypdfium2 as pdfium
    from PIL import ImageDraw
    import pypdf
    import io

    src     = pdfium.PdfDocument(path)
    writer  = pypdf.PdfWriter(clone_from=path)
    touched = 0
    orphaned = []          # annotations removed, to prune from the AcroForm

    try:
        for i, items in sorted(edits_by_page.items()):
            if not items or i >= len(src):
                continue

            page   = src[i]
            pw, ph = page.get_width(), page.get_height()
            # Checked before rasterizing: afterwards there is no text left to
            # tell whether the page ever had any.
            had_text = restore_text_layer and _page_has_text(page)
            img    = page.render(scale=dpi / 72.0).to_pil().convert("RGB")
            iw, ih = img.size

            draw_page(ImageDraw.Draw(img, "RGBA"), items, iw / pw, ih / ph, pw, ph)

            raster = _searchable_page(img, dpi) if had_text else None
            if raster is None:
                buf = io.BytesIO()
                img.save(buf, "PDF", resolution=dpi)
                buf.seek(0)
                raster = pypdf.PdfReader(buf).pages[0]

            _stamp_page_content(writer.pages[i], raster)
            target = writer.pages[i]

            if drop_annots_in:
                orphaned += _remove_annotations_within(
                    target, drop_annots_in(items, pw, ph))

            touched += 1

        _prune_acroform_fields(writer, orphaned)
    finally:
        src.close()

    with open(output, "wb") as fh:
        writer.write(fh)
    return touched


def _searchable_page(image, dpi: int):
    """
    Turn a rendered page image back into a page with an invisible text layer.

    Used after redaction so the content that was *not* removed stays
    searchable and selectable. Returns None when OCR is unavailable or fails —
    the caller falls back to the plain image, because losing searchability is
    a far better outcome than failing a redaction that has already been
    computed.
    """
    tess = find_tesseract()
    if not tess:
        return None

    try:
        import pytesseract
        import pypdf
        import io

        pytesseract.pytesseract.tesseract_cmd = tess
        page_pdf = pytesseract.image_to_pdf_or_hocr(
            image, extension="pdf", config=f"--dpi {dpi}")
        pages = pypdf.PdfReader(io.BytesIO(page_pdf)).pages
        return pages[0] if pages else None
    except Exception as e:
        print(f"[redact] could not restore the text layer: {e}", flush=True)
        return None


def _stamp_page_content(target, replacement) -> None:
    """
    Replace a page's drawing content with another page's, in place.

    Used by every operation that rebuilds a page — redaction, flattening, OCR.
    Replacing content rather than swapping the whole page keeps the page
    object, so its annotations, its form widgets and the document catalog that
    references it all survive. Rebuilding pages into a fresh writer instead
    dropped /AcroForm, which is why OCR'ing a document used to leave it with
    no fillable fields at all.

    A replacement of a different size is scaled to the target's box; a page
    produced at a different DPI than the original would otherwise be stamped
    at the wrong scale and clipped.
    """
    from pypdf import Transformation

    target.replace_contents(None)

    target_width  = float(target.mediabox.width)
    target_height = float(target.mediabox.height)
    source_width  = float(replacement.mediabox.width)
    source_height = float(replacement.mediabox.height)

    if (source_width <= 0 or source_height <= 0
            or (abs(source_width - target_width) < 0.5
                and abs(source_height - target_height) < 0.5)):
        target.merge_page(replacement)
        return

    target.merge_transformed_page(replacement, Transformation().scale(
        target_width / source_width, target_height / source_height))


def _remove_annotations_within(page, rects: list) -> list:
    """
    Delete annotations whose rectangle overlaps any of the given areas, and
    return what was removed.

    Rasterizing destroys the pixels but not the objects drawn on top of them,
    so a form field or note sitting over a redacted region would keep its
    contents in the file. Rectangles are in PDF points, origin bottom-left.
    """
    from pypdf.generic import ArrayObject, NameObject

    annots = page.get("/Annots")
    if not annots or not rects:
        return []

    def overlaps(rect) -> bool:
        try:
            ax0, ay0, ax1, ay1 = (float(v) for v in rect)
        except (TypeError, ValueError):
            return False
        lo_x, hi_x = min(ax0, ax1), max(ax0, ax1)
        lo_y, hi_y = min(ay0, ay1), max(ay0, ay1)
        return any(lo_x < rx1 and hi_x > rx0 and lo_y < ry1 and hi_y > ry0
                   for rx0, ry0, rx1, ry1 in rects)

    kept, removed = ArrayObject(), []
    for ref in annots:
        if overlaps(ref.get_object().get("/Rect", [])):
            removed.append(ref)
        else:
            kept.append(ref)

    page[NameObject("/Annots")] = kept
    return removed


def _prune_acroform_fields(writer, orphaned: list) -> None:
    """
    Drop redacted widgets from the document's AcroForm field list.

    Removing a widget from its page is not enough on its own: the field is
    also listed in the catalog's /AcroForm /Fields, and a field reachable from
    there keeps its value in the file whatever the page shows. For a
    single-widget field — what most generators emit — the widget and the field
    are one object, so the same reference appears in both places.
    """
    if not orphaned:
        return

    from pypdf.generic import ArrayObject, IndirectObject, NameObject

    acroform = writer._root_object.get("/AcroForm")
    if acroform is None:
        return
    # /AcroForm is normally an indirect reference. Assigning through the
    # reference updates the proxy, not the dictionary the catalog points at,
    # so resolve it before writing back.
    acroform = acroform.get_object()
    if "/Fields" not in acroform:
        return

    def key(ref):
        return ref.idnum if isinstance(ref, IndirectObject) else id(ref)

    removed = {key(ref) for ref in orphaned}
    acroform[NameObject("/Fields")] = ArrayObject([
        ref for ref in acroform["/Fields"] if key(ref) not in removed
    ])


def _redaction_rects(regions: list, page_w: float, page_h: float) -> list:
    """Redacted areas as (x0, y0, x1, y1) in PDF points, origin bottom-left."""
    rects = []
    for region in regions:
        x = float(region.get("x", 0))
        y = float(region.get("y", 0))
        w = float(region.get("width", 100))
        h = float(region.get("height", 20))
        rects.append((x, y, x + w, y + h))
    return rects


def _draw_redactions(draw, regions, scale_x, scale_y, page_w, page_h):
    """Burn opaque black boxes over each region (PDF points, origin bottom-left)."""
    for region in regions:
        rx = float(region.get("x", 0))
        ry = float(region.get("y", 0))
        rw = float(region.get("width", 100))
        rh = float(region.get("height", 20))
        draw.rectangle(
            [int(rx * scale_x),        int((page_h - ry - rh) * scale_y),
             int((rx + rw) * scale_x), int((page_h - ry) * scale_y)],
            fill=(0, 0, 0)
        )


def _draw_shapes(draw, shapes, scale_x, scale_y, page_w, page_h):
    """
    Paint annotation shapes onto a rasterized page. Shape coordinates are
    top-left origin (the frontend's screen space at zoom 1, which equals PDF
    points), unlike redaction regions.
    """
    from PIL.ImageColor import getrgb

    for shape in shapes:
        tool  = shape.get("tool", "rect")
        try:
            rgb = getrgb(shape.get("color", "#FF0000"))
        except Exception:
            rgb = (255, 0, 0)
        width = max(1, int(float(shape.get("strokeWidth", 2)) * scale_x * 0.5))

        if tool in ("line", "arrow", "dimension"):
            draw.line([float(shape.get("x1", 0)) * scale_x, float(shape.get("y1", 0)) * scale_y,
                       float(shape.get("x2", 0)) * scale_x, float(shape.get("y2", 0)) * scale_y],
                      fill=rgb, width=width)

        elif tool in ("rect", "highlight", "redact", "ellipse",
                      "underline", "strikeout", "squiggly"):
            x = float(shape.get("x", 0)) * scale_x
            y = float(shape.get("y", 0)) * scale_y
            w = float(shape.get("width", 100)) * scale_x
            h = float(shape.get("height", 50)) * scale_y
            if tool == "redact":
                draw.rectangle([x, y, x + w, y + h], fill=(0, 0, 0))
            elif tool == "ellipse":
                draw.ellipse([x, y, x + w, y + h], outline=rgb, width=width)
            elif tool == "underline":
                draw.line([x, y + h, x + w, y + h], fill=rgb, width=width)
            elif tool == "strikeout":
                draw.line([x, y + h / 2, x + w, y + h / 2], fill=rgb, width=width)
            elif tool == "squiggly":
                draw.line([x, y + h, x + w, y + h], fill=rgb, width=width)
            else:
                opacity = int(float(shape.get("opacity", 0.15)) * 255)
                draw.rectangle([x, y, x + w, y + h],
                               outline=rgb, width=width, fill=(*rgb, opacity))

        elif tool == "circle":
            cx = float(shape.get("cx", 0)) * scale_x
            cy = float(shape.get("cy", 0)) * scale_y
            r  = float(shape.get("r", 50)) * scale_x
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=rgb, width=width)

        elif tool in ("freehand", "cloud", "polygon", "polyline"):
            pts = shape.get("points", [])
            if len(pts) >= 2:
                scaled = [(p["x"] * scale_x, p["y"] * scale_y) for p in pts]
                if tool == "polygon":
                    scaled.append(scaled[0])   # close the ring
                draw.line(scaled, fill=rgb, width=width)

        elif tool in ("text", "stamp", "callout", "note"):
            text = shape.get("text", "")
            if text:
                draw.text((float(shape.get("x", 0)) * scale_x,
                           float(shape.get("y", 0)) * scale_y), text, fill=rgb)


# ══════════════════════════════════════════════════════════════════
#  OCR: SCANNED PDF -> SEARCHABLE PDF
# ══════════════════════════════════════════════════════════════════

def _page_has_text(page, min_chars: int = 10) -> bool:
    """
    True if a pypdfium2 page already carries an extractable text layer.
    Used to skip pages that don't need OCR — re-OCRing a digital page
    would rasterize it and *lose* quality and real text for no gain.
    """
    try:
        textpage = page.get_textpage()
        text     = textpage.get_text_range() or ""
        textpage.close()
        return len(text.strip()) >= min_chars
    except Exception:
        return False


def ocr_pdf_to_searchable(path: str, output: str = "", lang: str = "eng",
                          dpi: int = 300, skip_text_pages: bool = True) -> dict:
    """
    Produce a searchable PDF from a scanned/image PDF by adding an invisible
    text layer, leaving the visible page appearance unchanged.

    Uses Tesseract's own PDF renderer (image_to_pdf_or_hocr) rather than
    positioning invisible text by hand — Tesseract already knows each glyph's
    exact box from recognition, so its output aligns text to the image
    correctly, which hand-placed word boxes reliably get subtly wrong.

    Pages that already have a text layer are copied through untouched
    (skip_text_pages=True), so this is safe to run on a mixed document.
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    tess = find_tesseract()
    if not tess:
        return {"success": False, "error": "TESSERACT_NOT_FOUND",
                "hint": "Install Tesseract OCR: 'sudo apt install tesseract-ocr' "
                        "(Linux) or https://github.com/UB-Mannheim/tesseract/wiki (Windows)"}

    # Guard rails: dpi below ~150 wrecks recognition accuracy, above 600 costs
    # a lot of time/memory for no measurable gain.
    dpi = max(150, min(int(dpi or 300), 600))

    try:
        import pypdfium2 as pdfium
        import pytesseract
        import pypdf
        import io

        pytesseract.pytesseract.tesseract_cmd = tess

        if not output:
            output = os.path.splitext(path)[0] + "_searchable.pdf"

        src        = pdfium.PdfDocument(path)
        # Clone rather than assembling pages into an empty writer: an empty
        # writer starts with an empty catalog, so /AcroForm was dropped and an
        # OCR'd document reported itself as having no fillable form fields —
        # which then made form-filling impossible on anything already OCR'd.
        writer     = pypdf.PdfWriter(clone_from=path)
        total      = len(src)
        ocr_pages  = 0
        kept_pages = 0

        print(f"[OCR] {total} page(s) @ {dpi}dpi lang={lang}", flush=True)

        for i in range(total):
            page = src[i]

            if skip_text_pages and _page_has_text(page):
                kept_pages += 1
                continue

            # pypdfium2's render scale is relative to 72dpi
            bitmap = page.render(scale=dpi / 72.0)
            img    = bitmap.to_pil().convert("RGB")

            # --dpi must be passed explicitly: Tesseract otherwise assumes
            # 70dpi for a bitmap carrying no resolution metadata and sizes
            # the output page from that, which silently inflated every
            # OCR'd page to (dpi/70)x its true dimensions.
            page_pdf = pytesseract.image_to_pdf_or_hocr(
                img, lang=lang, extension="pdf", config=f"--dpi {dpi}"
            )
            recognised = pypdf.PdfReader(io.BytesIO(page_pdf)).pages
            if recognised:
                _stamp_page_content(writer.pages[i], recognised[0])
            ocr_pages += 1

        src.close()

        with open(output, "wb") as fh:
            writer.write(fh)

        print(f"[OCR] done — {ocr_pages} OCR'd, {kept_pages} already searchable", flush=True)

        return {
            "success": True,
            "outputPath": output,
            "totalPages": total,
            "ocrPages": ocr_pages,
            "skippedPages": kept_pages,
            "language": lang,
            "dpi": dpi,
        }

    except ImportError as e:
        return {"success": False,
                "error": f"Required library missing: {e}",
                "hint": "pip install pypdfium2 pytesseract pypdf Pillow"}
    except pytesseract.TesseractError as e:
        # Most common cause: requested language pack not installed
        return {"success": False, "error": f"Tesseract failed: {e}",
                "hint": f"Is the '{lang}' language pack installed? "
                        f"e.g. 'sudo apt install tesseract-ocr-{lang}'"}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ══════════════════════════════════════════════════════════════════
#  PHASE 3: FLATTEN ANNOTATIONS TO PDF
# ══════════════════════════════════════════════════════════════════

def flatten_annotations_to_pdf(path: str, shapes: list, output: str = "", quality: str = "screen") -> dict:
    """
    Flatten annotation shapes onto a PDF by rendering pages to images
    and drawing the shapes using PIL.

    shapes: [{tool, pageNumber, color, strokeWidth, x1,y1,x2,y2, ...}]
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    try:
        if not output:
            base   = os.path.splitext(path)[0]
            output = base + "_annotated.pdf"

        dpi     = FLATTEN_DPI.get(quality, FLATTEN_DPI["screen"])
        by_page = _group_by_page(shapes, key="pageNumber")

        flattened_pages = _rewrite_pdf_pages(
            path, output, dpi, by_page, _draw_shapes
        )

        return {
            "success":        True,
            "outputPath":     output,
            "flattenedPages": flattened_pages,
            "shapes":         len(shapes),
            "dpi":            dpi
        }

    except ImportError as e:
        return {"success": False, "error": f"Missing library: {e}. Run: pip install pypdfium2 pypdf Pillow"}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ══════════════════════════════════════════════════════════════════
#  PHASE 3: PDF FORM FILLING — see pdf_forms.py
# ══════════════════════════════════════════════════════════════════
# Re-exported rather than moved behind a namespace: the request handler
# below and the tests both reach these by name off this module.
from pdf_forms import (  # noqa: E402,F401
    FF_READ_ONLY, FF_REQUIRED, FF_MULTILINE, FF_PASSWORD, FF_RADIO,
    FF_PUSHBUTTON, FF_COMBO, FF_MULTISELECT, TRUTHY,
    _form_field_kind, _choice_options, _checkbox_states,
    _qualified_field_name, _field_widget_index, _inherited_attr,
    _is_truthy, _strip_form_interactivity,
    fill_pdf_form, inspect_pdf_form, describe_pdf_pages,
)


def rearrange_pdf_pages(path: str, plan: list, output: str = "",
                        sources: dict = None) -> dict:
    """
    Rebuild a PDF from an explicit list of pages.

    One primitive covers every page operation the UI offers, because each is
    just a different plan over the same pages:

        delete     omit the page from the plan
        reorder    list the pages in the new order
        duplicate  list a page more than once
        rotate     keep the order, give the entry a non-zero rotate
        insert     give the entry a source other than this document
        extract    the same call, writing to a new document

    Expressing them separately would mean six near-identical rebuild loops
    with six chances to get page indexing wrong.

    plan: [{"source": <key or null>, "page": <1-based>, "rotate": <degrees>}]
      source  null/omitted means this document; otherwise a key into sources
      rotate  applied *relative* to the page's existing /Rotate, so the
              caller can say "turn this 90° clockwise" without first
              reading what it already is

    sources: {key: path} for pages taken from other documents

    Pages keep their annotations and the document keeps its AcroForm, so a
    reordered document is still fillable and still carries its markup.
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}
    if not plan:
        return {"success": False, "error": "No pages selected — the result would be empty"}

    try:
        import pypdf

        readers = {None: pypdf.PdfReader(path)}
        for key, source_path in (sources or {}).items():
            if not source_path or not os.path.exists(source_path):
                return {"success": False, "error": f"Source document not found: {key}"}
            readers[key] = pypdf.PdfReader(source_path)

        # Validate the whole plan before writing anything: a half-applied
        # rearrangement is worse than a rejected one.
        for position, entry in enumerate(plan, start=1):
            key = entry.get("source")
            if key not in readers:
                return {"success": False,
                        "error": f"Entry {position} refers to unknown source '{key}'"}
            number = int(entry.get("page", 0))
            available = len(readers[key].pages)
            if not 1 <= number <= available:
                return {"success": False,
                        "error": f"Entry {position} asks for page {number}, "
                                 f"but that document has {available}"}

        if not output:
            output = os.path.splitext(path)[0] + "_pages.pdf"

        # Clone so the catalog — AcroForm above all — survives, then replace
        # the page tree. Building into an empty writer would drop the form,
        # exactly as it did for redaction and OCR.
        writer = pypdf.PdfWriter(clone_from=path)
        original_pages = list(writer.pages)

        writer.flattened_pages = None
        for _ in range(len(original_pages)):
            writer.remove_page(0)

        rotated = 0
        placed  = set()
        for entry in plan:
            key    = entry.get("source")
            number = int(entry.get("page", 1))
            turn   = int(entry.get("rotate", 0) or 0) % 360

            if key is None:
                original = original_pages[number - 1]
                if (key, number) in placed:
                    # A page object can appear in the tree only once — adding
                    # the same one twice makes the tree cyclic and every later
                    # read of the file fails. Copies of a duplicated page get
                    # their own objects; the first instance keeps the original
                    # so its form widgets stay attached to the AcroForm.
                    page = original.clone(writer, force_duplicate=True)
                else:
                    page = original
            else:
                # Pages from another document are cloned by add_page anyway.
                page = readers[key].pages[number - 1]

            placed.add((key, number))
            added = writer.add_page(page)
            if turn:
                added.rotate(turn)
                rotated += 1

        with open(output, "wb") as fh:
            writer.write(fh)

        return {
            "success":    True,
            "outputPath": output,
            "pageCount":  len(plan),
            "sourcePages": len(original_pages),
            "rotatedPages": rotated,
        }

    except ImportError as e:
        return {"success": False, "error": f"pypdf required: {e}"}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ══════════════════════════════════════════════════════════════════
#  PHASE 3: IFC MODEL TREE
# ══════════════════════════════════════════════════════════════════

def extract_ifc_tree(path: str) -> dict:
    """
    Extract a hierarchical spatial structure from an IFC file.
    Returns: [{id, name, type, children, expanded, selected, visible}]
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    try:
        import ifcopenshell

        ifc = ifcopenshell.open(path)

        def get_children(element) -> list:
            children = []
            try:
                for rel in getattr(element, 'IsDecomposedBy', []):
                    for child in rel.RelatedObjects:
                        children.append(element_to_node(child))
            except Exception:
                pass
            # Also include contained elements for spaces/storeys
            try:
                for rel in getattr(element, 'ContainsElements', []):
                    for child in rel.RelatedElements:
                        children.append(element_to_node(child))
            except Exception:
                pass
            return children

        def element_to_node(element) -> dict:
            name = getattr(element, 'Name', None) or element.is_a()
            return {
                "id":       str(element.GlobalId),
                "name":     str(name),
                "type":     element.is_a(),
                "expanded": False,
                "selected": False,
                "visible":  True,
                "children": get_children(element)
            }

        # Build tree from IfcProject root
        projects = ifc.by_type("IfcProject")
        if not projects:
            return {"success": False, "error": "No IfcProject found in file"}

        tree = [element_to_node(p) for p in projects]
        return {"success": True, "tree": tree, "schema": ifc.schema}

    except ImportError:
        return {"success": False, "error": "ifcopenshell required"}
    except Exception as e:
        return {"success": False, "error": str(e)}



if __name__ == "__main__":
    if not EZDXF_OK:
        print(f'ERROR: ezdxf not installed.\nRun: python -m pip install "ezdxf[draw]"',
              file=sys.stderr)
        sys.exit(1)

    lo    = find_libreoffice()
    # Probed at startup rather than on first use, so an ODA that is mounted
    # but cannot run is a line in the boot log instead of a drawing that
    # fails months later for a reason nobody connects to the mount.
    oda   = oda_status()

    tess  = find_tesseract()

    # Create C:\Temp on Windows for short paths
    if IS_WINDOWS:
        try:
            os.makedirs("C:\\Temp", exist_ok=True)
            print(f"[INFO] Using C:\\Temp for temp files (avoids path spaces)", flush=True)
        except Exception:
            print("[WARN] Could not create C:\\Temp — using system temp", flush=True)

    print(f"CDE Converter  |  {platform.system()}  |  ezdxf {ezdxf.__version__}  |  port {PORT}")
    print(f"  LibreOffice : {lo   or 'NOT FOUND'}")
    print(f"  ODA (DWG)   : {oda['path'] or 'not configured'}"
          f"{'' if not oda['installed'] else ('  [ok] ' if oda['runnable'] else '  [UNUSABLE] ')}"
          f"{oda['detail'] if oda['installed'] else '— DWG disabled, every other format works'}")
    print(f"  Tesseract   : {tess or 'NOT FOUND — scanned PDF OCR disabled'}")
    print(f"  POST /convert  body: {{\"path\": \"<absolute_path>\", \"contentType\": \"<mime>\"}}")
    print(flush=True)

    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


