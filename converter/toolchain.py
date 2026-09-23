"""
Talking to the machine the converter runs on: temp space, subprocesses, and
finding the command-line tools.

Lifted out of ``app.py`` unchanged. Everything here is shared — the DXF
renderer, the comparison engine and the OCR path all need temp directories
and a bounded ``run_cmd`` — so it has to sit below them in the import order
rather than beside them, or the modules that use it cannot be extracted
without a cycle.
"""
import os
import platform
import shutil
import subprocess
import tempfile
from pathlib import Path

IS_WINDOWS = platform.system() == "Windows"

#  TEMP DIR — use short path on Windows to avoid spaces
# ══════════════════════════════════════════════════════════════
def make_temp_dir():
    """
    On Windows, tempfile defaults to C:\\Users\\...\\AppData\\Local\\Temp
    which has spaces. Use C:\\Temp instead if it exists or can be created,
    as short paths are safer for legacy tools like ODA.
    """
    if IS_WINDOWS:
        base = "C:\\Temp"
        try:
            os.makedirs(base, exist_ok=True)
            return tempfile.mkdtemp(dir=base)
        except Exception:
            pass  # fall through to default
    return tempfile.mkdtemp()


def safe_rmtree(path):
    try:
        shutil.rmtree(path, ignore_errors=True)
    except Exception:
        pass


# ══════════════════════════════════════════════════════════════
#  SUBPROCESS — Windows-safe, handles spaces in paths
# ══════════════════════════════════════════════════════════════
def run_cmd(cmd, timeout=120):
    """
    Run command list. On Windows uses shell=False with proper quoting.
    All paths in cmd should already be absolute strings.
    Returns (returncode, stdout, stderr).
    """
    log_cmd = " ".join(f'"{c}"' if " " in str(c) else str(c) for c in cmd)
    print(f"[CMD] {log_cmd}", flush=True)

    kwargs = dict(timeout=timeout)

    if IS_WINDOWS:
        si = subprocess.STARTUPINFO()
        si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        si.wShowWindow = 0  # SW_HIDE
        kwargs["startupinfo"] = si
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
        # On Windows, pass cmd as list (Python handles quoting internally)
        kwargs["shell"] = False

    try:
        r = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            **kwargs
        )
        stdout = r.stdout.decode("utf-8", errors="replace") if r.stdout else ""
        stderr = r.stderr.decode("utf-8", errors="replace") if r.stderr else ""
        print(f"[CMD] rc={r.returncode} stdout={stdout[:150]} stderr={stderr[:150]}", flush=True)
        return r.returncode, stdout, stderr
    except subprocess.TimeoutExpired:
        print("[CMD] TIMEOUT", flush=True)
        return -1, "", f"Timed out after {timeout}s"
    except FileNotFoundError as e:
        print(f"[CMD] NOT FOUND: {e}", flush=True)
        return -2, "", f"Executable not found: {e}"
    except Exception as e:
        print(f"[CMD] ERROR: {e}", flush=True)
        return -3, "", str(e)


# ══════════════════════════════════════════════════════════════
#  LIBREOFFICE
# ══════════════════════════════════════════════════════════════
def find_libreoffice():
    if IS_WINDOWS:
        for base in [r"C:\Program Files", r"C:\Program Files (x86)"]:
            if not os.path.isdir(base):
                continue
            for entry in sorted(os.listdir(base), reverse=True):
                if "libreoffice" in entry.lower():
                    exe = os.path.join(base, entry, "program", "soffice.exe")
                    if os.path.isfile(exe):
                        return exe
        # Explicit fallbacks
        for p in [
            r"C:\Program Files\LibreOffice\program\soffice.exe",
            r"C:\Program Files (x86)\LibreOffice\program\soffice.exe",
        ]:
            if os.path.isfile(p): return p
    else:
        for cmd in ["libreoffice", "soffice"]:
            found = shutil.which(cmd)
            if found: return found
        mac = "/Applications/LibreOffice.app/Contents/MacOS/soffice"
        if os.path.isfile(mac): return mac
    return None


def libreoffice_to_pdf(file_path: str) -> dict:
    """
    Convert any file LibreOffice can open into PDF bytes.

    Named for what it does rather than for its first caller: Office documents
    were the only input when it was written, and SVG — the print render of a
    CAD drawing — now goes through the same conversion.
    """
    lo = find_libreoffice()
    if not lo:
        return {"success": False, "error":
            "LibreOffice not installed.\n"
            "Ubuntu: sudo apt install libreoffice\n"
            "Windows: https://www.libreoffice.org/download"}

    abs_path = str(Path(file_path).resolve())
    out_dir = make_temp_dir()
    try:
        cmd = [lo, "--headless", "--norestore", "--nofirststartwizard",
               "--convert-to", "pdf", "--outdir", out_dir, abs_path]
        rc, stdout, stderr = run_cmd(cmd, timeout=90)

        # LibreOffice on Windows prints harmless warnings to stderr — ignore them
        # Check for actual output files
        all_files = list(Path(out_dir).iterdir())
        print(f"[LO] out_dir={out_dir} files={all_files}", flush=True)

        # Use rglob to find PDF anywhere in out_dir (LO sometimes makes subfolders)
        pdf_files = list(set(Path(out_dir).rglob("*.pdf")))
        if not pdf_files:
            # LO may have written to the SOURCE file's directory instead of out_dir
            # This happens when --outdir is ignored (rare LO bug on Windows)
            src_dir = Path(abs_path).parent
            fallback = list(src_dir.glob(Path(abs_path).stem + "*.pdf"))
            print(f"[LO] Fallback PDF search in {src_dir}: {fallback}", flush=True)
            if fallback:
                return {"success": True, "pdfBytes": fallback[0].read_bytes()}
            return {"success": False, "error":
                f"LibreOffice produced no PDF (rc={rc}).\n"
                f"stdout={stdout[:300]}\nstderr={stderr[:200]}"}

        print(f"[LO] PDF found: {pdf_files[0]}", flush=True)
        return {"success": True, "pdfBytes": pdf_files[0].read_bytes()}
    finally:
        safe_rmtree(out_dir)


# ══════════════════════════════════════════════════════════════
#  ODA FILE CONVERTER
# ══════════════════════════════════════════════════════════════
ODA_BINARY_NAME = "ODAFileConverter.exe" if IS_WINDOWS else "ODAFileConverter"

# What to tell someone whose DWG would not open. It travels in the failure
# payload rather than living only in the documentation, because the person
# who hits this is looking at an error, not at a README — and the previous
# error, LIBREDWG_NOT_FOUND, named a tool that is no longer the answer.
DWG_REMEDY = (
    "DWG requires the ODA File Converter, which cannot be redistributed and so "
    "is not in this image. Download it from opendesign.com, mount the extracted "
    "installation at /opt/oda (or set ODA_PATH), and restart. Every other "
    "format — DXF, PDF, Office and IFC — works without it."
)


def find_tesseract():
    """
    Locate the Tesseract OCR binary. Single source of truth — the same
    discovery was previously inlined in three places (text extraction,
    startup banner, health check) with slightly different candidate lists.
    """
    found = shutil.which("tesseract")
    if found:
        return found
    if IS_WINDOWS:
        for p in [
            r"C:\Program Files\Tesseract-OCR\tesseract.exe",
            r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
            r"C:\Users\{}\AppData\Local\Programs\Tesseract-OCR\tesseract.exe".format(
                os.environ.get("USERNAME", "")),
        ]:
            if os.path.isfile(p):
                return p
    p = os.environ.get("TESSERACT_PATH", "")
    return p if p and os.path.isfile(p) else None


# ══════════════════════════════════════════════════════════════
#  ezdxf DXF -> SVG
# ══════════════════════════════════════════════════════════════
