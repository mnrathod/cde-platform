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
from ifc_geometry import (  # noqa: E402,F401
    GEOMETRY_MAGIC, GEOMETRY_VERSION,
    is_revit, extract_ifc_geometry, assemble_geometry_buckets,
    _geometry_meta, ifc_geometry_json, encode_geometry_container,
    ifc_geometry_binary,
)
from ifc_tree import extract_ifc_tree  # noqa: E402,F401
from pdf_text_search import (  # noqa: E402,F401
    REDACTION_PATTERNS, _MATCH_PADDING_PT,
    _compile_search_patterns, _match_rects, find_text_matches, _group_by_page,
)
from pdf_page_rewrite import (  # noqa: E402,F401
    _rewrite_pdf_pages, _searchable_page, _stamp_page_content,
    _remove_annotations_within, _prune_acroform_fields,
    _redaction_rects, _draw_redactions, _draw_shapes, _page_has_text,
)
from pdf_ocr import ocr_pdf_to_searchable  # noqa: E402,F401
from pdf_redaction import (  # noqa: E402,F401
    REDACTION_DPI, FLATTEN_DPI, redact_pdf, flatten_annotations_to_pdf,
)
from pdf_pages import rearrange_pdf_pages  # noqa: E402,F401
from conversion import is_dwg, convert, compare_files  # noqa: E402,F401
from http_api import Handler  # noqa: E402,F401
from pdf_forms import (  # noqa: E402,F401
    FF_READ_ONLY, FF_REQUIRED, FF_MULTILINE, FF_PASSWORD, FF_RADIO,
    FF_PUSHBUTTON, FF_COMBO, FF_MULTISELECT, TRUTHY,
    _form_field_kind, _choice_options, _checkbox_states,
    _qualified_field_name, _field_widget_index, _inherited_attr,
    _is_truthy, _strip_form_interactivity,
    fill_pdf_form, inspect_pdf_form, describe_pdf_pages,
)

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


