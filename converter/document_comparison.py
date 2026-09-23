"""
Pulling text out of a document, and diffing two of them.

<p>The CAD and IFC comparisons live in ``cad_comparison.py``, images and
unlike-kind fallbacks in ``image_comparison.py``, and the dispatch that
chooses between all three stays in ``app.py``. What is here is text.

<p>Extraction has a deliberate ladder of fallbacks per format rather than
one library call, because a PDF may be a structured document, a scan, or
something in between, and only trying tells you which.
"""
import os
from pathlib import Path

from file_types import OFFICE_EXTS
from toolchain import (
    make_temp_dir, safe_rmtree, run_cmd,
    find_libreoffice, find_tesseract,
)


def _extract_text(path: str, ext: str) -> str:
    """
    Extract plain text from PDF or Office file.
    PDF pipeline: pdfplumber → pypdf → LibreOffice → OCR (tesseract)
    Office pipeline: LibreOffice text export → python-docx fallback
    """
    if ext == 'pdf':

        # ── Method 1: pdfplumber (best for structured PDFs) ──────
        try:
            import pdfplumber
            pages = []
            with pdfplumber.open(path) as pdf:
                for page in pdf.pages:
                    # Try text extraction
                    t = page.extract_text(x_tolerance=2, y_tolerance=2)
                    if t and t.strip():
                        pages.append(t.strip())
                    # Also extract tables as text
                    try:
                        for table in page.extract_tables():
                            for row in table:
                                row_text = '  |  '.join(str(c or '') for c in row)
                                if row_text.strip():
                                    pages.append(row_text)
                    except Exception:
                        pass
            text = '\n'.join(pages)
            if text.strip():
                print(f"[PDF] pdfplumber: {len(text)} chars from {len(pages)} pages", flush=True)
                return text
            print("[PDF] pdfplumber returned empty — trying pypdf", flush=True)
        except Exception as e:
            print(f"[PDF] pdfplumber error: {e}", flush=True)

        # ── Method 2: pypdf ──────────────────────────────────────
        try:
            import pypdf
            reader = pypdf.PdfReader(path)
            # Check if PDF is encrypted
            if reader.is_encrypted:
                try:
                    reader.decrypt('')  # try empty password
                except Exception:
                    print("[PDF] PDF is password-protected", flush=True)
                    return ""
            pages = []
            for page in reader.pages:
                t = page.extract_text()
                if t and t.strip():
                    pages.append(t.strip())
            text = '\n'.join(pages)
            if text.strip():
                print(f"[PDF] pypdf: {len(text)} chars", flush=True)
                return text
            print("[PDF] pypdf returned empty — likely scanned PDF", flush=True)
        except Exception as e:
            print(f"[PDF] pypdf error: {e}", flush=True)

        # ── Method 3: pdftotext CLI (poppler) ────────────────────
        try:
            rc, stdout, stderr = run_cmd(['pdftotext', '-layout', path, '-'], timeout=30)
            if rc == 0 and stdout.strip():
                print(f"[PDF] pdftotext: {len(stdout)} chars", flush=True)
                return stdout
        except Exception:
            pass

        # ── Method 4: OCR via pypdfium2 + Tesseract (Apache 2.0) ──
        # pypdfium2 replaces PyMuPDF (AGPL) — no Poppler needed, commercial-safe
        try:
            import pypdfium2 as pdfium
            import pytesseract
            from PIL import Image

            tess = find_tesseract()
            if not tess:
                raise FileNotFoundError("Tesseract not found")
            pytesseract.pytesseract.tesseract_cmd = tess

            print("[PDF] OCR with pypdfium2 + Tesseract...", flush=True)
            pdf_doc = pdfium.PdfDocument(path)
            pages = []
            max_pages = min(len(pdf_doc), 10)
            for i in range(max_pages):
                pg = pdf_doc[i]
                bitmap = pg.render(scale=2)   # 2x ≈ 144dpi
                img = bitmap.to_pil()
                t = pytesseract.image_to_string(img, lang='eng')
                if t and t.strip():
                    pages.append(t.strip())
            pdf_doc.close()
            text = '\n'.join(pages)
            if text.strip():
                print(f"[PDF] OCR: {len(text)} chars from {max_pages} pages", flush=True)
                return text
            print("[PDF] OCR returned empty — PDF may be blank", flush=True)
        except ImportError as e:
            print(f"[PDF] OCR import error: {e} — run: pip install pypdfium2 pytesseract Pillow", flush=True)
        except FileNotFoundError as e:
            print(f"[PDF] Tesseract not found: {e}", flush=True)
        except Exception as e:
            print(f"[PDF] OCR error: {e}", flush=True)

        # ── Method 5: LibreOffice text export ────────────────────
        lo = find_libreoffice()
        if lo:
            out_dir = make_temp_dir()
            try:
                run_cmd([lo, '--headless', '--norestore', '--convert-to', 'txt:Text',
                         '--outdir', out_dir, path], timeout=60)
                txt_files = list(Path(out_dir).glob('*.txt'))
                if txt_files:
                    text = txt_files[0].read_text(encoding='utf-8', errors='replace')
                    if text.strip():
                        print(f"[PDF] LibreOffice: {len(text)} chars", flush=True)
                        return text
            finally:
                safe_rmtree(out_dir)

        print("[PDF] All extraction methods failed", flush=True)
        return ""

    elif ext in OFFICE_EXTS:
        # ── LibreOffice text export ───────────────────────────────
        lo = find_libreoffice()
        if lo:
            out_dir = make_temp_dir()
            try:
                run_cmd([lo, '--headless', '--norestore', '--convert-to', 'txt:Text',
                         '--outdir', out_dir, path], timeout=60)
                txt_files = list(Path(out_dir).glob('*.txt'))
                if txt_files:
                    text = txt_files[0].read_text(encoding='utf-8', errors='replace')
                    if text.strip():
                        return text
            finally:
                safe_rmtree(out_dir)

        # ── python-docx fallback for .docx ────────────────────────
        if ext == 'docx':
            try:
                import docx
                doc = docx.Document(path)
                return '\n'.join(p.text for p in doc.paragraphs if p.text.strip())
            except ImportError:
                pass

        # ── pdfplumber fallback for PDF-like office formats ───────
        if ext in ('xlsx', 'xls'):
            try:
                import openpyxl
                wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
                rows = []
                for ws in wb.worksheets:
                    for row in ws.iter_rows(values_only=True):
                        r = '  |  '.join(str(c) for c in row if c is not None)
                        if r.strip():
                            rows.append(r)
                return '\n'.join(rows)
            except ImportError:
                pass

    return ""


def _compare_documents(path1, path2, ext1, ext2) -> dict:
    import difflib

    print(f"[DOC] Extracting text from: {Path(path1).name}", flush=True)
    text1 = _extract_text(path1, ext1)
    print(f"[DOC] Extracting text from: {Path(path2).name}", flush=True)
    text2 = _extract_text(path2, ext2)
    print(f"[DOC] Text lengths: {len(text1)}, {len(text2)}", flush=True)

    # ── Get PDF metadata (page count, size) for fallback comparison ──
    def pdf_meta(path):
        meta = {'pages': 0, 'size_kb': round(os.path.getsize(path) / 1024, 1)}
        try:
            import pypdf
            reader = pypdf.PdfReader(path)
            meta['pages'] = len(reader.pages)
            meta['encrypted'] = reader.is_encrypted
        except Exception:
            pass
        return meta

    m1 = pdf_meta(path1) if ext1 == 'pdf' else {}
    m2 = pdf_meta(path2) if ext2 == 'pdf' else {}

    # ── Both failed — return metadata diff + helpful guidance ────
    if not text1 and not text2:
        changes = []
        if m1 and m2:
            if m1['pages'] != m2['pages']:
                diff = m2['pages'] - m1['pages']
                changes.append({
                    'category': 'STRUCTURE', 'severity': 'high',
                    'type': 'added' if diff > 0 else 'removed',
                    'icon': '📄',
                    'change': f"Page count changed: {m1['pages']} → {m2['pages']} pages",
                    'detail': f"{'Added' if diff>0 else 'Removed'} {abs(diff)} page{'s' if abs(diff)>1 else ''}"
                })
            if abs(m1['size_kb'] - m2['size_kb']) > 10:
                changes.append({
                    'category': 'STRUCTURE', 'severity': 'medium',
                    'type': 'modified', 'icon': '💾',
                    'change': f"File size changed: {m1['size_kb']} KB → {m2['size_kb']} KB",
                    'detail': f"{'Larger' if m2['size_kb'] > m1['size_kb'] else 'Smaller'} file may indicate content changes"
                })
        return {
            'success': True,
            'fileType': 'PDF (Scanned/Image)',
            'overall': 'metadata only — text not extractable',
            'totalChanges': len(changes),
            'added': 0, 'removed': 0,
            'changes': changes,
            'warning': (
                'These PDFs appear to be scanned images with no text layer. '
                'Only structural metadata (page count, file size) could be compared.\n\n'
                'To enable full text comparison, either:\n'
                '• Install Tesseract OCR: sudo apt install tesseract-ocr (Ubuntu) or https://github.com/UB-Mannheim/tesseract/wiki (Windows)\n'
                '• Or use text-based PDFs instead of scanned images'
            ),
            'stats': {
                'file1_pages': m1.get('pages', '?'), 'file2_pages': m2.get('pages', '?'),
                'file1_size_kb': m1.get('size_kb', 0), 'file2_size_kb': m2.get('size_kb', 0),
            }
        }

    # ── One file failed — still do partial diff ───────────────────
    if not text1 or not text2:
        failed_file = "File 1" if not text1 else "File 2"
        ok_text     = text2 if not text1 else text1
        ok_lines    = len([l for l in ok_text.splitlines() if l.strip()])
        meta_failed = m1 if not text1 else m2

        changes = [{
            'category': 'EXTRACTION', 'severity': 'high',
            'type': 'modified', 'icon': '⚠️',
            'change': f"{failed_file} could not be read as text",
            'detail': (f"Likely a scanned/image PDF ({meta_failed.get('pages','?')} pages, "
                       f"{meta_failed.get('size_kb','?')} KB). "
                       f"Install Tesseract OCR for full comparison.")
        }]

        # Page count diff if available
        if m1.get('pages') and m2.get('pages') and m1['pages'] != m2['pages']:
            diff = m2['pages'] - m1['pages']
            changes.append({
                'category': 'STRUCTURE', 'severity': 'medium',
                'type': 'added' if diff > 0 else 'removed', 'icon': '📄',
                'change': f"Page count: {m1['pages']} → {m2['pages']}",
                'detail': f"{'Added' if diff>0 else 'Removed'} {abs(diff)} page(s)"
            })

        return {
            'success': True,
            'fileType': 'Document (partial)',
            'overall': f'{failed_file} unreadable — partial comparison only',
            'totalChanges': len(changes),
            'added': 0, 'removed': 0,
            'changes': changes,
            'warning': (
                f'{failed_file} appears to be a scanned/image PDF with no text layer.\n'
                'Install Tesseract OCR for full text comparison:\n'
                '  Ubuntu: sudo apt install tesseract-ocr\n'
                '  Windows: https://github.com/UB-Mannheim/tesseract/wiki'
            ),
            'stats': {
                'file1_lines': ok_lines if not text2 else 0,
                'file2_lines': ok_lines if not text1 else 0,
            }
        }

    # ── Both have text — do full diff ──────────────────────────────
    lines1 = [l.strip() for l in text1.splitlines() if l.strip()]
    lines2 = [l.strip() for l in text2.splitlines() if l.strip()]

    changes = []
    diff = list(difflib.ndiff(lines1, lines2))
    added   = [l[2:] for l in diff if l.startswith('+ ')]
    removed = [l[2:] for l in diff if l.startswith('- ')]

    for line in added[:20]:
        changes.append({'category':'CONTENT','severity':'medium','type':'added',
                        'icon':'➕','change':f"Added: \"{line[:100]}\"",
                        'detail':'New content in revised document'})
    for line in removed[:20]:
        changes.append({'category':'CONTENT','severity':'medium','type':'removed',
                        'icon':'➖','change':f"Removed: \"{line[:100]}\"",
                        'detail':'Content removed from original'})

    overall = ('identical' if not changes else
               'minor differences' if len(changes) <= 3 else
               'moderate changes' if len(changes) <= 10 else
               'significant changes')

    return {
        'success': True, 'fileType': 'Document',
        'overall': overall,
        'totalChanges': len(changes),
        'added': len(added), 'removed': len(removed),
        'changes': changes,
        'stats': {'file1_lines': len(lines1), 'file2_lines': len(lines2),
                  'lines_added': len(added), 'lines_removed': len(removed)},
    }


