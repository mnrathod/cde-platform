"""
Tests for the ODA File Converter integration.

ODA converts DWG to DXF at higher fidelity than the bundled LibreDWG and is
tried first when present. The binary cannot ship with the product — its licence
forbids redistribution — so everything *around* it is ours to get right, and
before this it was not: the image had no virtual display, the invocation did not
ask for one, and ODA is a Qt application that opens a display even converting
from the command line. A mounted ODA was configured, advertised in the README,
and could not start.

The failure had no symptom. ODA aborts, the code falls through to LibreDWG, the
drawing converts, and the only trace is fidelity nobody is measuring. So these
tests cover the two halves of making it real: that it is launched in a way that
can work, and that when it cannot, something says so.

A stand-in binary stands for the real one throughout — ODA is registration-gated
and cannot be a fixture in this repository. What that pins is the wiring, which
is where the defect was; it is not a test of ODA's own conversion.
"""
import os
import stat

import pytest

import app


@pytest.fixture
def oda_stub(tmp_path):
    """
    A stand-in that behaves like ODA: refuses to start without a display.

    The refusal is the point. A stub that always succeeded would pass whether
    or not the display wrapper was applied, which is exactly the bug.
    """
    install = tmp_path / "oda"
    install.mkdir()
    binary = install / "ODAFileConverter"
    binary.write_text(
        "#!/bin/sh\n"
        'if [ -z "$DISPLAY" ]; then\n'
        '  echo "qt.qpa.xcb: could not connect to display" >&2\n'
        "  exit 1\n"
        "fi\n"
        'echo "converted in=$1 out=$2 version=$3"\n'
        "exit 0\n"
    )
    binary.chmod(binary.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return install


@pytest.fixture(autouse=True)
def forget_probe_result():
    """The probe caches; each test needs its own answer."""
    app._ODA_STATUS = None
    yield
    app._ODA_STATUS = None


class TestFindingIt:

    def test_a_directory_resolves_to_the_binary_inside_it(self, oda_stub, monkeypatch):
        # Pointing ODA_PATH at the extracted install is the natural thing to do
        # and used to find nothing at all.
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        assert app.find_oda() == str(oda_stub / "ODAFileConverter")

    def test_the_binary_itself_resolves_too(self, oda_stub, monkeypatch):
        monkeypatch.setenv("ODA_PATH", str(oda_stub / "ODAFileConverter"))
        assert app.find_oda() == str(oda_stub / "ODAFileConverter")

    def test_a_file_without_the_execute_bit_is_not_found(self, oda_stub, monkeypatch):
        # A read-only mount that dropped the execute bit is the commonest way
        # this arrives broken. Reporting it as found would send the DWG path
        # into a binary that cannot run instead of to LibreDWG.
        binary = oda_stub / "ODAFileConverter"
        binary.chmod(binary.stat().st_mode & ~0o111)
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        assert app.find_oda() is None

    def test_an_empty_setting_finds_nothing_rather_than_the_cwd(self, monkeypatch):
        monkeypatch.setenv("ODA_PATH", "")
        monkeypatch.setattr(app.shutil, "which", lambda name: None)
        monkeypatch.setattr(app.os.path, "isfile", lambda p: False)
        assert app.find_oda() is None


class TestLaunchingItHeadless:

    def test_a_headless_host_gets_a_virtual_display(self, monkeypatch):
        # The whole fix in one assertion: without this prefix ODA aborts on
        # every container in every deployment.
        monkeypatch.delenv("DISPLAY", raising=False)
        monkeypatch.setattr(app.shutil, "which",
                            lambda name: "/usr/bin/xvfb-run" if name == "xvfb-run" else None)
        assert app.oda_launch_prefix() == ["/usr/bin/xvfb-run", "-a"]

    def test_the_display_number_is_not_fixed(self, monkeypatch):
        # -a, so two conversions at once do not collide on one display and
        # fail for a reason that has nothing to do with their drawings.
        monkeypatch.delenv("DISPLAY", raising=False)
        monkeypatch.setattr(app.shutil, "which", lambda name: "/usr/bin/xvfb-run")
        assert "-a" in app.oda_launch_prefix()

    def test_an_existing_display_is_used_as_is(self, monkeypatch):
        # A developer running this on a desktop should not be put behind an
        # X server they already have.
        monkeypatch.setenv("DISPLAY", ":0")
        assert app.oda_launch_prefix() == []

    def test_without_xvfb_it_runs_anyway_rather_than_refusing(self, monkeypatch):
        # Degrade, do not block: some hosts have a display by another route,
        # and the probe reports the truth either way.
        monkeypatch.delenv("DISPLAY", raising=False)
        monkeypatch.setattr(app.shutil, "which", lambda name: None)
        assert app.oda_launch_prefix() == []

    def test_the_conversion_call_carries_the_prefix(self, oda_stub, monkeypatch, tmp_path):
        # Asserted on the argv actually built, because the prefix existing and
        # the prefix being used are different things.
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        monkeypatch.setattr(app, "oda_launch_prefix", lambda: ["/usr/bin/xvfb-run", "-a"])

        seen = []

        def record(cmd, timeout=120):
            seen.append(cmd)
            return 0, "", ""

        monkeypatch.setattr(app, "run_cmd", record)
        source = tmp_path / "plan.dwg"
        source.write_bytes(b"AC1032" + b"\0" * 32)

        app.dwg_via_oda(str(source), lambda content: {"success": False, "error": "stub"})

        assert seen, "ODA was never invoked"
        assert seen[0][:2] == ["/usr/bin/xvfb-run", "-a"], seen[0][:4]
        assert seen[0][2].endswith("ODAFileConverter")


class TestSayingWhetherItWorks:

    def test_a_working_install_reports_runnable(self, oda_stub, monkeypatch):
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        status = app.probe_oda()
        assert status["installed"] is True
        assert status["runnable"] is True, status["detail"]

    def test_an_install_that_cannot_start_is_reported_not_merely_present(
            self, oda_stub, monkeypatch):
        # The failure this whole probe exists for. Before it, health said
        # "odaInstalled: true" and DWG quietly converted at LibreDWG fidelity.
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        monkeypatch.delenv("DISPLAY", raising=False)
        monkeypatch.setattr(app.shutil, "which", lambda name: None)

        status = app.probe_oda()
        assert status["installed"] is True
        assert status["runnable"] is False
        assert "cannot start" in status["detail"]
        assert "display" in status["detail"].lower(), \
            "the reason has to name the cause or nobody can act on it"

    def test_no_install_is_reported_as_a_fallback_not_a_fault(self, monkeypatch):
        # Absent ODA is the supported default, so it must not read as broken.
        monkeypatch.setattr(app, "find_oda", lambda: None)
        status = app.probe_oda()
        assert status == {"installed": False, "runnable": False, "path": None,
                          "detail": "not configured — DWG falls back to LibreDWG"}

    def test_the_probe_runs_once(self, oda_stub, monkeypatch):
        # /health is polled every 30 seconds; starting a process each time to
        # answer a question that changes only on redeploy would be a steady
        # waste for no new information.
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        calls = []
        monkeypatch.setattr(app, "probe_oda",
                            lambda *a, **k: calls.append(1) or {"installed": True})

        app.oda_status()
        app.oda_status()
        app.oda_status()
        assert len(calls) == 1

    def test_a_refresh_re_probes(self, monkeypatch):
        calls = []
        monkeypatch.setattr(app, "probe_oda",
                            lambda *a, **k: calls.append(1) or {"installed": False})
        app.oda_status()
        app.oda_status(refresh=True)
        assert len(calls) == 2

    def test_the_probe_does_not_leave_temporary_directories_behind(
            self, oda_stub, monkeypatch):
        # It runs on every start of every converter pod; leaking two
        # directories each time fills a disk slowly enough not to be noticed.
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        made = []
        real_make = app.make_temp_dir
        monkeypatch.setattr(app, "make_temp_dir",
                            lambda: made.append(real_make()) or made[-1])

        app.probe_oda()

        assert made, "the probe did not run"
        assert not [d for d in made if os.path.exists(d)], \
            f"left behind: {[d for d in made if os.path.exists(d)]}"


class TestTheFallbackIsIntact:

    def test_dwg_still_converts_with_no_oda_at_all(self, monkeypatch, tmp_path):
        # ODA is an upgrade, never a requirement. If this breaks, every
        # deployment without ODA loses DWG entirely.
        monkeypatch.setattr(app, "find_oda", lambda: None)
        monkeypatch.setattr(app, "find_dwg2dxf", lambda: "/usr/local/bin/dwg2dxf")

        extracted = tmp_path / "out.dxf"

        def fake_run(cmd, timeout=120):
            extracted.write_text("DXF CONTENT")
            return 0, "", ""

        monkeypatch.setattr(app, "run_cmd", fake_run)
        monkeypatch.setattr(app, "make_temp_dir", lambda: str(tmp_path))
        # Stubbed: what is being asserted is which extractor the DWG was
        # routed to, not whether ezdxf can render this particular text.
        monkeypatch.setattr(app, "render_dxf_string",
                            lambda content: {"success": True, "svg": "<svg/>"})

        source = tmp_path / "plan.dwg"
        source.write_bytes(b"AC1032" + b"\0" * 32)

        result = app.convert(str(source), "application/dwg")
        assert result.get("convertedBy") == "LibreDWG", result


class TestWhatTheUserIsTold:

    def test_a_broken_oda_is_not_reported_to_the_user_as_installed(
            self, oda_stub, monkeypatch, tmp_path):
        # This payload reaches whoever's drawing would not open. Saying ODA is
        # installed when it cannot start sends them to look for a fault in the
        # file — the same class of untrue message as telling someone a drawing
        # could not be converted when it converted fine.
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        monkeypatch.delenv("DISPLAY", raising=False)
        monkeypatch.setattr(app.shutil, "which", lambda name: None)
        monkeypatch.setattr(app, "find_dwg2dxf", lambda: None)

        source = tmp_path / "plan.dwg"
        source.write_bytes(b"AC1032" + b"\0" * 32)

        result = app.convert(str(source), "application/dwg")
        assert result["error"] == "DWG_NEED_CONVERTER"
        assert result["odaInstalled"] is False, \
            "a binary that cannot start is not available to convert anything"
        assert "cannot start" in result["odaDetail"]

    def test_a_working_oda_is_reported_as_installed(
            self, oda_stub, monkeypatch, tmp_path):
        monkeypatch.setenv("ODA_PATH", str(oda_stub))
        monkeypatch.setattr(app, "find_dwg2dxf", lambda: None)
        # The stub converts nothing, so extraction still fails and the payload
        # is still built — which is exactly the case being checked.
        source = tmp_path / "plan.dwg"
        source.write_bytes(b"AC1032" + b"\0" * 32)

        result = app.convert(str(source), "application/dwg")
        assert result["error"] == "DWG_NEED_CONVERTER"
        assert result["odaInstalled"] is True
