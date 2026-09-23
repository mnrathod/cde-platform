"""
Reading and filling a PDF's AcroForm fields.

Lifted out of ``app.py`` unchanged. It was the most self-contained thing in
a 3,596-line module: nothing here touches temp directories, subprocesses,
LibreOffice or the ODA converter, and nothing outside it needs the flag
constants. ``app`` re-exports every name so both the request handler and the
tests — which reach for the private helpers by name — keep working.

The flag constants are AcroForm field flags (PDF 32000-1, tables 226/228/230),
stored as a single ``/Ff`` integer whose bits mean different things per field
type.
"""
import os

FF_READ_ONLY   = 1 << 0
FF_REQUIRED    = 1 << 1
FF_MULTILINE   = 1 << 12   # text
FF_PASSWORD    = 1 << 13   # text
FF_RADIO       = 1 << 15   # button
FF_PUSHBUTTON  = 1 << 16   # button
FF_COMBO       = 1 << 17   # choice
FF_MULTISELECT = 1 << 21   # choice

TRUTHY = {"true", "yes", "on", "1", "y", "checked"}


def _form_field_kind(field_type: str, flags: int) -> str:
    """Map a raw /FT plus its flag bits onto a control the UI can render."""
    if field_type == "/Btn":
        if flags & FF_PUSHBUTTON: return "button"
        if flags & FF_RADIO:      return "radio"
        return "checkbox"
    if field_type == "/Ch":
        return "dropdown" if flags & FF_COMBO else "listbox"
    if field_type == "/Sig":
        return "signature"
    if flags & FF_PASSWORD:  return "password"
    if flags & FF_MULTILINE: return "textarea"
    return "text"


def _choice_options(field) -> list:
    """
    Normalise /Opt into {value,label} pairs. Entries are either a bare
    string, or an [export_value, display_label] pair.
    """
    options = []
    for opt in (field.get("/Opt") or []):
        if isinstance(opt, (list, tuple)):
            export  = str(opt[0])
            display = str(opt[1]) if len(opt) > 1 else export
        else:
            export = display = str(opt)
        options.append({"value": export, "label": display})
    return options


def _checkbox_states(field) -> tuple:
    """
    A checkbox's "on" value is whatever its appearance dictionary calls it —
    commonly /Yes but just as legitimately /On or /1 — so it has to be read
    from the field rather than assumed.
    """
    states = [str(s) for s in (field.get("/_States_") or [])
              if not isinstance(s, (list, tuple))]
    on_state = next((s for s in states if s != "/Off"), "/Yes")
    return on_state, "/Off"


def _qualified_field_name(node) -> str:
    """
    Build a field's fully qualified name (parent.child) by walking up the
    /Parent chain, matching the keys PdfReader.get_fields() returns.
    """
    parts, guard = [], 0
    while node is not None and guard < 32:
        title = node.get("/T")
        if title is not None:
            parts.append(str(title))
        parent = node.get("/Parent")
        node = parent.get_object() if parent is not None else None
        guard += 1
    return ".".join(reversed(parts))


def _field_widget_index(reader) -> dict:
    """
    Map field name -> {"page": 1-based page, "widget": widget dictionary}.

    Widget annotations carry attributes that PdfReader.get_fields() does not
    surface — /MaxLen among them, which only ever appears here — so anything
    beyond /T /FT /Ff /V /DV has to be read off the page's /Annots directly.
    """
    index = {}
    for page_number, page in enumerate(reader.pages, start=1):
        for annot in (page.get("/Annots") or []):
            try:
                widget = annot.get_object()
                name = _qualified_field_name(widget)
            except Exception:
                continue
            if name and name not in index:
                index[name] = {"page": page_number, "widget": widget}
    return index


def _inherited_attr(node, key, depth: int = 8):
    """
    Read an AcroForm attribute, following /Parent — field attributes may be
    declared on an ancestor rather than the widget itself.
    """
    guard = 0
    while node is not None and guard < depth:
        if key in node:
            return node[key]
        parent = node.get("/Parent")
        node = parent.get_object() if parent is not None else None
        guard += 1
    return None


def _is_truthy(value) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lstrip("/").lower() in TRUTHY


def _strip_form_interactivity(writer) -> None:
    """
    Remove widget annotations and the AcroForm dictionary.

    pypdf's flatten=True paints each field's value into the page content
    stream but leaves the interactive field in place, so the result still
    opens as an editable form. Dropping the widgets and the AcroForm makes
    the painted values the only remaining representation.
    """
    from pypdf.generic import NameObject, ArrayObject

    for page in writer.pages:
        annots = page.get("/Annots")
        if not annots:
            continue
        kept = ArrayObject([
            a for a in annots
            if str(a.get_object().get("/Subtype", "")) != "/Widget"
        ])
        if len(kept):
            page[NameObject("/Annots")] = kept
        elif "/Annots" in page:
            del page[NameObject("/Annots")]

    root = writer.root_object
    if "/AcroForm" in root:
        del root[NameObject("/AcroForm")]


def fill_pdf_form(path: str, fields: dict, output: str = "", flatten: bool = False) -> dict:
    """
    Fill PDF AcroForm fields.
    fields: {"FieldName": "Value", ...}

    Values are coerced per field type — checkboxes and radios need their
    appearance-state name (/Yes, /On, ...), not a stringified boolean.
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    try:
        from pypdf import PdfReader, PdfWriter

        reader      = PdfReader(path)
        form_fields = reader.get_fields() or {}
        if not form_fields:
            return {"success": False,
                    "error": "This PDF has no fillable form fields (no AcroForm found)"}

        writer = PdfWriter(clone_from=path)

        resolved, skipped = {}, {}
        for name, raw_value in (fields or {}).items():
            field = form_fields.get(name)
            if field is None:
                skipped[name] = "no such field in this PDF"
                continue

            flags = int(field.get("/Ff", 0) or 0)
            if flags & FF_READ_ONLY:
                skipped[name] = "field is read-only"
                continue

            kind = _form_field_kind(str(field.get("/FT", "/Tx")), flags)
            if kind in ("checkbox", "radio"):
                on_state, off_state = _checkbox_states(field)
                resolved[name] = on_state if _is_truthy(raw_value) else off_state
            else:
                resolved[name] = "" if raw_value is None else str(raw_value)

        if resolved:
            # Apply across ALL pages. Updating only page 0 left every field on
            # a later page blank while still reporting it as filled.
            writer.update_page_form_field_values(
                list(writer.pages), resolved, auto_regenerate=False, flatten=flatten
            )
            if flatten:
                _strip_form_interactivity(writer)
            else:
                # Without this many viewers render a filled field as empty,
                # because no appearance stream was generated for the new value.
                writer.set_need_appearances_writer(True)

        if not output:
            base   = os.path.splitext(path)[0]
            output = base + "_filled.pdf"

        with open(output, "wb") as f_out:
            writer.write(f_out)

        return {
            "success":         True,
            "outputPath":      output,
            "filledFields":    resolved,
            "skippedFields":   skipped,
            "availableFields": list(form_fields.keys())
        }

    except ImportError:
        return {"success": False, "error": "pypdf required. Run: pip install pypdf"}
    except Exception as e:
        return {"success": False, "error": str(e)}


# Inspect form fields without filling
def inspect_pdf_form(path: str) -> dict:
    """
    Describe every AcroForm field in enough detail for a client to render
    the right control and validate input: the resolved kind, choice options,
    current value, read-only/required state, max length and page number.
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    try:
        import pypdf
        reader = pypdf.PdfReader(path)
        fields  = reader.get_fields() or {}
        widgets = _field_widget_index(reader)

        described = []
        for name, field in fields.items():
            field_type = str(field.get("/FT", "/Tx"))
            flags      = int(field.get("/Ff", 0) or 0)
            kind       = _form_field_kind(field_type, flags)
            raw_value  = field.get("/V", "")

            entry = {
                "name":      name,
                "kind":      kind,
                "type":      field_type,
                "flags":     flags,
                "readOnly":  bool(flags & FF_READ_ONLY),
                "required":  bool(flags & FF_REQUIRED),
                "page":      widgets.get(name, {}).get("page", 1),
            }

            if kind in ("checkbox", "radio"):
                on_state, _ = _checkbox_states(field)
                entry["onState"] = on_state
                entry["checked"] = str(raw_value) == on_state
                entry["value"]   = str(raw_value or "/Off")
            else:
                entry["value"] = "" if raw_value is None else str(raw_value)

            if kind in ("dropdown", "listbox"):
                entry["options"]     = _choice_options(field)
                entry["multiSelect"] = bool(flags & FF_MULTISELECT)

            if kind in ("text", "textarea", "password"):
                widget  = widgets.get(name, {}).get("widget")
                max_len = _inherited_attr(widget, "/MaxLen") if widget is not None else None
                if max_len is not None:
                    entry["maxLength"] = int(max_len)
                entry["multiline"] = bool(flags & FF_MULTILINE)

            described.append(entry)

        # Stable, page-then-name ordering so the form UI doesn't reshuffle
        # between requests (dict order follows the PDF's internal layout).
        described.sort(key=lambda f: (f["page"], f["name"]))

        return {
            "success":   True,
            "fields":    described,
            "count":     len(described),
            "pageCount": len(reader.pages)
        }
    except Exception as e:
        return {"success": False, "error": str(e)}


# ══════════════════════════════════════════════════════════════════
#  PAGE MANIPULATION
# ══════════════════════════════════════════════════════════════════

def describe_pdf_pages(path: str) -> dict:
    """
    Report each page's size and rotation.

    The page organiser needs to know how many pages there are and which way
    up each one sits before it can offer to reorder or rotate them, and it
    must not infer that from the rendered thumbnails — a page can carry a
    /Rotate that the renderer has already applied.
    """
    if not path or not os.path.exists(path):
        return {"success": False, "error": f"File not found: {path}"}

    try:
        import pypdf

        reader = pypdf.PdfReader(path)
        pages = []
        for index, page in enumerate(reader.pages):
            box = page.mediabox
            pages.append({
                "page":     index + 1,
                "width":    round(float(box.width), 1),
                "height":   round(float(box.height), 1),
                "rotation": int(page.get("/Rotate", 0) or 0) % 360,
            })

        return {"success": True, "pageCount": len(pages), "pages": pages}

    except ImportError as e:
        return {"success": False, "error": f"pypdf required: {e}"}
    except Exception as e:
        return {"success": False, "error": str(e)}

