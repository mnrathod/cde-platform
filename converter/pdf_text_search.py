"""
Finding the text in a PDF that a redaction is meant to cover.

<p>Separate from the redaction itself because finding and covering are
different jobs with different failure modes: this one answers "where does
this appear", and it has to be right about the rectangles, because a
redaction drawn from a wrong rectangle looks exactly like one drawn from a
right one and covers the wrong words.

<p>Matching runs over the page's extracted text rather than per-word, so a
phrase spanning a line break is still found.
"""
import os
import re


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


