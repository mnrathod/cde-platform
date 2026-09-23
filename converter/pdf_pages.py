"""
Reordering, rotating, deleting and inserting a PDF's pages.

<p>Structural edits only: nothing here touches page content, which is what
keeps it separate from redaction and flattening. A plan describes the pages
the result should have and where each comes from, so one pass produces the
new document rather than a sequence of edits each rewriting the file.
"""
import os


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


