"""
Deciding what to do with a file, and doing it.

<p>One entry point for every format the service handles, because the caller
knows it has a file and a purpose, not which of a dozen pipelines applies.
The routing is on what the file actually is — magic bytes before extension,
since the extension is the client's claim — and the purpose comes from the
requested target format.

<p>The comparison dispatch sits beside it for the same reason: the caller
asks for two files to be compared, and which of the four comparisons that
means is this module's problem, not theirs.
"""
import os
import traceback
from pathlib import Path

from cad_comparison import _compare_cad, _compare_ifc
from document_comparison import _compare_documents
from dwg_oda import dwg_via_oda, find_oda, oda_status
from dxf_render import dxf_file_to_pdf, dxf_string_to_pdf, render_dxf, render_dxf_string
from file_types import DWG_VERSIONS, OFFICE_EXTS
from ifc_geometry import ifc_geometry_json, is_revit
from image_comparison import _compare_images, _compare_metadata
from toolchain import DWG_REMEDY, find_libreoffice, libreoffice_to_pdf


def is_dwg(path: str) -> tuple:
    try:
        with open(path, "rb") as f:
            magic = f.read(6).decode("ascii", errors="replace")
        if magic.startswith("AC"):
            return True, DWG_VERSIONS.get(magic[:6], magic[:6])
    except Exception:
        pass
    return False, ""


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


