# ══════════════════════════════════════════════════════════════
#  CDE Converter — document/CAD conversion microservice
#
#  Packages the external toolchains app.py shells out to. Without
#  them the service starts happily and then fails at request time,
#  which is how Office conversion silently did nothing in a
#  container while working on a developer machine:
#
#    LibreOffice  →  Office (doc/xls/ppt/odf) → PDF
#    Tesseract    →  OCR for scanned PDFs
#
#  DXF renders with no external tool at all — ezdxf (MIT) reads it
#  directly. DWG is the one format with no binary here: it needs
#  the ODA File Converter, which the operator supplies. See the
#  "DWG" note at the bottom of this file for why.
# ══════════════════════════════════════════════════════════════

# ── Stage 1: Python dependencies ──────────────────────────────
# Built into a venv so the runtime stage copies one directory and
# never fights Ubuntu's PEP 668 externally-managed-environment.
FROM ubuntu:24.04@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517 AS python-builder

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-venv python3-dev build-essential ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r /tmp/requirements.txt


# ── Stage 2: runtime ──────────────────────────────────────────
FROM ubuntu:24.04@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517 AS runtime

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH" \
    CONVERTER_PORT=5001

# libreoffice-{writer,calc,impress} rather than the `libreoffice`
# metapackage: same conversion filters, a fraction of the size, and
# none of Base/Draw/Math. The fonts matter — without them LibreOffice
# substitutes metric-incompatible faces and page breaks shift, so a
# converted PDF no longer matches the source document.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        libreoffice-writer \
        libreoffice-calc \
        libreoffice-impress \
        tesseract-ocr \
        tesseract-ocr-eng \
        fonts-dejavu-core \
        fonts-liberation2 \
        ca-certificates \
        curl \
        xvfb \
    && rm -rf /var/lib/apt/lists/*

# xvfb (X.Org, MIT) is here for the ODA File Converter, which is a Qt
# application and opens a display even when converting from the command
# line — on a headless container it aborts before reading its arguments.
# ~1 MB of package, and without it a mounted ODA is present and unusable
# — which, now that ODA is the only DWG route, means DWG simply does not
# work and the reason is a display error nobody would look for. app.py
# wraps ODA in xvfb-run itself, so an operator mounts the binary and
# nothing else.

# The venv is copied wholesale, so this stage's python3 must be the same
# minor version as python-builder's — both are ubuntu:24.04 (3.12) for that
# reason. Changing one base image without the other breaks every import, so
# both stages are pinned to one digest and scripts/check-pinning.sh fails
# the build if they diverge. requirements.txt is resolved against 3.12 too;
# see REQUIRED_PYTHON_MINOR in lock-requirements.sh.
COPY --from=python-builder /opt/venv /opt/venv

# Non-root. LibreOffice needs a writable HOME for its user profile —
# with an unwritable one `soffice --convert-to` exits non-zero and
# every Office conversion fails.
RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin converter
ENV HOME=/home/converter

WORKDIR /app
COPY --chown=converter:converter app.py ./
RUN mkdir -p /app/uploads && chown converter:converter /app/uploads

USER converter
EXPOSE 5001

# /health reports which of the toolchains above were actually found,
# so a misbuilt image fails the healthcheck instead of failing later
# on a user's upload.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://localhost:${CONVERTER_PORT}/health" || exit 1

ENTRYPOINT ["python3", "app.py"]

# ── DWG: requires the ODA File Converter, supplied by the operator ───
# This image contains no DWG reader. It used to bundle LibreDWG, built
# from source in a first stage — that worked, and shipping the binary
# put a GPL-3.0 §6 corresponding-source obligation on every copy of this
# image we distribute (ADR 13, docs/licences.md §4.1). A product a
# customer installs is distribution in the plainest sense, so the binary
# is gone rather than the obligation being carried.
#
# ODA is the replacement and was always the better converter — app.py
# already tried it first. Everything it needs is in this image — xvfb
# above, the headless wrapper and the startup probe in app.py — except
# the binary itself, which cannot be baked in either: its download is
# registration-gated and its licence forbids redistribution. That is the
# point rather than a limitation. The customer licences ODA directly, so
# the obligation is theirs and we distribute no encumbered binary at all.
#
# Everything except DWG is unaffected. DXF renders through ezdxf (MIT)
# with no external tool, and Office, PDF and IFC never touched LibreDWG.
#
# Mount the extracted installation and point ODA_PATH at it. Either the
# directory or the binary works:
#
#   volumes:
#     - ./vendor/oda:/opt/oda:ro
#   environment:
#     ODA_PATH: /opt/oda
#
# /opt/oda is also searched by default, so a mount at that path needs no
# environment variable at all. The startup log and /health report whether
# ODA was found *and whether it ran* — check `odaRunnable`, not just
# `odaInstalled`, because a mount missing its libraries or its execute
# bit is present and useless. Without a runnable ODA, DWG conversion
# fails with DWG_NEED_CONVERTER and says so; every other format works.
