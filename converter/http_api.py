"""
The HTTP surface: one POST endpoint per operation, and a health probe.

<p>Deliberately a plain ``BaseHTTPRequestHandler`` rather than a framework.
This service does one thing behind the application, on a private network,
with no sessions, no templating and no routing beyond a handful of paths —
a framework here would be a dependency to patch and an EOL clock to watch
(§0.2, §0.3) in exchange for nothing.

<p>The health response reports ``installed`` and ``runnable`` separately for
ODA, because the gap between them is exactly where a mounted converter goes
wrong and a single boolean hides it.
"""
import json
import os
import platform
import shutil
import traceback
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlparse

from conversion import compare_files, convert
from dwg_oda import oda_status
from dxf_render import EZDXF_OK, ezdxf
from ifc_geometry import ifc_geometry_binary
from ifc_tree import extract_ifc_tree
from pdf_forms import describe_pdf_pages, fill_pdf_form, inspect_pdf_form
from pdf_ocr import ocr_pdf_to_searchable
from pdf_pages import rearrange_pdf_pages
from pdf_redaction import flatten_annotations_to_pdf, redact_pdf
from pdf_text_search import find_text_matches
from toolchain import find_libreoffice, find_tesseract


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
            # Copied through a bounded buffer rather than read whole (§7.7).
            # A GLB of a real building is hundreds of megabytes, and holding
            # one per concurrent request is how this process runs out of
            # memory serving files it never needed to look at.
            source = result["filePath"]
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(os.path.getsize(source)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            with open(source, "rb") as model:
                shutil.copyfileobj(model, self.wfile, 64 * 1024)

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



