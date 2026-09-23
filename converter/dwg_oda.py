"""
Finding the ODA File Converter, checking it can run, and driving it.

<p>DWG is a closed format with no readable open-source parser we may use,
so every DWG the service handles is converted to DXF by ODA's free
converter first and read from there. That makes ODA a hard external
dependency, and most of this file is about failing usefully when it is
absent, unrunnable, or too old for the drawing in hand — because the
alternative is a customer's first DWG upload reporting a bad drawing when
the drawing is fine.
"""
import os
import shutil
import time
from pathlib import Path

from toolchain import (
    IS_WINDOWS, ODA_BINARY_NAME, DWG_REMEDY,
    make_temp_dir, safe_rmtree, run_cmd,
)


def _oda_candidate(path: str):
    """
    Resolve one configured location to a runnable binary.

    Accepts the binary or the directory holding it, because both are natural
    things to point ODA_PATH at and half of them silently found nothing
    before. Executability is checked rather than existence: a mount that
    dropped the execute bit is the commonest way this arrives broken, and
    "the file is there" is not the question worth answering.
    """
    if not path:
        return None
    if os.path.isdir(path):
        path = os.path.join(path, ODA_BINARY_NAME)
    if os.path.isfile(path) and os.access(path, os.X_OK):
        return path
    return None


def find_oda():
    if IS_WINDOWS:
        for base in [r"C:\Program Files\ODA", r"C:\Program Files (x86)\ODA"]:
            if not os.path.isdir(base):
                continue
            for entry in sorted(os.listdir(base), reverse=True):
                found = _oda_candidate(os.path.join(base, entry))
                if found:
                    return found

    for path in [os.environ.get("ODA_PATH", ""),
                 "/opt/oda", "/usr/bin/ODAFileConverter",
                 "/usr/local/bin/ODAFileConverter"]:
        found = _oda_candidate(path)
        if found:
            return found
    return shutil.which(ODA_BINARY_NAME)


def oda_launch_prefix() -> list:
    """
    What has to run in front of ODA for it to start on a headless host.

    ODA File Converter is a Qt application and opens a display even when
    converting from the command line, so on a container with no X server it
    aborts before reading its arguments. Wrapping it in a virtual framebuffer
    is the whole fix, and doing it here rather than in a deployment note is
    what makes a mounted ODA work rather than merely be configured.

    Returns an empty prefix where a display already exists, so a developer
    running this on a desktop is not put behind an unnecessary X server.
    """
    if IS_WINDOWS or os.environ.get("DISPLAY"):
        return []
    xvfb_run = shutil.which("xvfb-run")
    if not xvfb_run:
        return []
    # -a picks a free display number: two conversions running at once would
    # otherwise collide on :99 and one would fail for a reason that has
    # nothing to do with its drawing.
    return [xvfb_run, "-a"]


def probe_oda(timeout: int = 30) -> dict:
    """
    Whether ODA can actually run here, not merely whether it is present.

    The two differ constantly: an install mounted without its shared
    libraries, without the execute bit, or onto a host with no display is
    present and unusable. Finding that out on the first DWG a customer
    uploads — where it looks like a bad drawing — is the failure this exists
    to prevent, so it is answered at startup and reported on /health.

    Run against an empty directory: ODA is asked to convert nothing, which
    exercises process start, library loading and the display without needing
    a DWG to hand.
    """
    oda = find_oda()
    if not oda:
        return {"installed": False, "runnable": False, "path": None,
                "detail": "not configured — DWG cannot be converted"}

    prefix = oda_launch_prefix()
    in_dir, out_dir = make_temp_dir(), make_temp_dir()
    try:
        rc, stdout, stderr = run_cmd(
            prefix + [oda, in_dir, out_dir, "ACAD2018", "DXF", "0", "0"],
            timeout=timeout)
        # The exit code is not the signal: ODA exits non-zero for an empty
        # input directory on some builds. A display failure is what matters
        # and it says so on stderr.
        output = f"{stdout}\n{stderr}".lower()
        broken = any(marker in output for marker in
                     ("cannot connect to x server", "could not connect to display",
                      "qt platform plugin", "no display", "error while loading shared"))
        if broken:
            return {"installed": True, "runnable": False, "path": oda,
                    "detail": f"present but cannot start: {stderr.strip()[:200]}"}
        return {"installed": True, "runnable": True, "path": oda,
                "detail": "headless via xvfb" if prefix else "native display"}
    except Exception as e:
        return {"installed": True, "runnable": False, "path": oda,
                "detail": f"present but could not be launched: {e}"}
    finally:
        safe_rmtree(in_dir)
        safe_rmtree(out_dir)


# Probed once. Running ODA on every health poll would cost a process start
# every 30 seconds to answer a question whose answer only changes when the
# deployment does.
_ODA_STATUS = None


def oda_status(refresh: bool = False) -> dict:
    global _ODA_STATUS
    if _ODA_STATUS is None or refresh:
        _ODA_STATUS = probe_oda()
    return _ODA_STATUS


def dwg_via_oda(dwg_path: str, render=None) -> dict:
    """
    DWG -> DXF via the ODA converter, then rendered by {@code render}.

    The renderer is injected because the extraction is identical whether the
    caller wants the viewer's SVG or an exported PDF, and duplicating the
    ODA version-fallback loop to vary only the last line is how the two
    would drift apart.
    """
    if render is None:
        # Imported here, not at the top: the renderer's module reads DXF,
        # which is what this produces, so a module-level import would be a
        # cycle. Deferring it also means a test that swaps the renderer out
        # is seen, because the lookup happens per call rather than at load.
        from dxf_render import render_dxf_string
        render = render_dxf_string
    oda = find_oda()
    if not oda:
        return {"success": False, "error": "ODA_NOT_FOUND"}

    abs_dwg = str(Path(dwg_path).resolve())

    # Use short C:\Temp paths to avoid spaces breaking ODA
    in_dir  = make_temp_dir()
    out_dir = make_temp_dir()

    try:
        dest = os.path.join(in_dir, Path(abs_dwg).name)
        shutil.copy2(abs_dwg, dest)
        print(f"[ODA] Copied DWG to: {dest}", flush=True)
        print(f"[ODA] Output dir:    {out_dir}", flush=True)
        print(f"[ODA] Executable:    {oda}", flush=True)

        # Prepended once, outside the loop: the display wrapper is a property
        # of the host, not of the DWG version being attempted.
        prefix = oda_launch_prefix()

        logs = []
        for version in ["ACAD2018", "ACAD2013", "ACAD2010", "ACAD2007"]:
            # ODA CLI: <inputDir> <outputDir> <version> <type> <recurse> <audit>
            # Pass as list — Python/Windows handles quoting for spaces automatically
            cmd = prefix + [oda, in_dir, out_dir, version, "DXF", "0", "1"]
            rc, stdout, stderr = run_cmd(cmd, timeout=120)
            logs.append(f"v={version} rc={rc} err={stderr[:150]}")

            # ODA can still be writing — wait for flush
            time.sleep(2)

            # Deduplicate (Windows rglob returns same file twice for *.dxf + *.DXF)
            dxf_files = list({str(f): f for f in
                (list(Path(out_dir).rglob("*.dxf")) +
                 list(Path(out_dir).rglob("*.DXF")))}.values())
            print(f"[ODA] DXF files after {version}: {dxf_files}", flush=True)

            if dxf_files:
                dxf_path = dxf_files[0]
                print(f"[ODA] Reading DXF: {dxf_path} ({dxf_path.stat().st_size} bytes)", flush=True)
                content = dxf_path.read_text(encoding="utf-8", errors="replace")
                print(f"[ODA] DXF content preview: {content[:120]!r}", flush=True)
                result = render(content)
                if not result.get("success"):
                    print(f"[ODA] ezdxf render failed: {result.get('error','?')}", flush=True)
                result["odaLog"] = "\n".join(logs)
                result["odaVersion"] = version
                return result

        return {"success": False,
                "error": "ODA ran but produced no DXF output.\n" + "\n".join(logs)}
    finally:
        safe_rmtree(in_dir)
        safe_rmtree(out_dir)


