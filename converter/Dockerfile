# ══════════════════════════════════════════════════════════════
#  CDE Converter — document/CAD conversion microservice
#
#  Packages the three external toolchains app.py shells out to.
#  Without them the service starts happily and then fails at
#  request time, which is how DWG and Office silently did nothing
#  in a container while working on a developer machine:
#
#    LibreOffice  →  Office (doc/xls/ppt/odf) → PDF
#    LibreDWG     →  binary DWG → DXF  (ezdxf renders DXF → SVG)
#    Tesseract    →  OCR for scanned PDFs
#
#  ODA File Converter is deliberately NOT baked in — see the
#  "Optional: ODA" note at the bottom of this file.
# ══════════════════════════════════════════════════════════════

# ── Stage 1: build LibreDWG ───────────────────────────────────
# LibreDWG is in no Debian or Ubuntu archive (`apt-cache search dwg`
# finds nothing), so binary DWG support has to be compiled. Built
# static so the runtime stage needs only the single executable and
# none of LibreDWG's shared libraries.
FROM ubuntu:24.04@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517 AS dwg-builder

ARG LIBREDWG_VERSION=0.13.3
ARG LIBREDWG_SHA256=6fe6c273ecbb04d4a7646e1636ede4815b51f98f974cece649dab341d24feda2

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl pkg-config \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN curl -fsSL -o libredwg.tar.gz \
        "https://github.com/LibreDWG/libredwg/releases/download/${LIBREDWG_VERSION}/libredwg-${LIBREDWG_VERSION}.tar.gz" \
    && echo "${LIBREDWG_SHA256}  libredwg.tar.gz" | sha256sum -c - \
    && tar xzf libredwg.tar.gz

WORKDIR /build/libredwg-${LIBREDWG_VERSION}
# --disable-bindings skips the SWIG Python/Perl wrappers, which we
# never call and which pull in a large build-time dependency set.
RUN ./configure \
        --disable-bindings \
        --disable-shared \
        --enable-static \
        --disable-dependency-tracking \
        --prefix=/usr/local \
    && make -j"$(nproc)" \
    && strip programs/dwg2dxf \
    && mkdir -p /out && cp programs/dwg2dxf /out/dwg2dxf


# ── Stage 2: Python dependencies ──────────────────────────────
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


# ── Stage 3: runtime ──────────────────────────────────────────
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
# ~1 MB of package, and without it a mounted ODA is present and unusable,
# which is a worse failure than not having it: DWG conversion silently
# stays at LibreDWG fidelity and nothing says why. app.py wraps ODA in
# xvfb-run itself, so an operator mounts the binary and nothing else.

# The venv is copied wholesale, so this stage's python3 must be the same
# minor version as python-builder's — both are ubuntu:24.04 (3.12) for that
# reason. Changing one base image without the other breaks every import, so
# all three stages are pinned to one digest and scripts/check-pinning.sh fails
# the build if they diverge. requirements.txt is resolved against 3.12 too;
# see REQUIRED_PYTHON_MINOR in lock-requirements.sh.
COPY --from=python-builder /opt/venv /opt/venv

# Copied from a fixed path rather than the versioned build directory, so
# bumping LIBREDWG_VERSION does not silently break this COPY (build ARGs
# are per-stage and are not in scope here).
COPY --from=dwg-builder /out/dwg2dxf /usr/local/bin/dwg2dxf

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

# ── ODA File Converter: supported, supplied by the operator ───
# ODA gives better DWG fidelity than LibreDWG and is tried first by
# app.py. Everything it needs is in this image — xvfb above, the
# headless wrapper and the startup probe in app.py — except the binary
# itself, which cannot be baked in: the download is registration-gated
# and its licence does not permit redistribution. See docs/licences.md.
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
# bit is present and useless. Without ODA the service falls back to
# LibreDWG, which is why DWG works out of the box.
