#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "========================================"
echo " CDE Platform — Ubuntu Setup & Start"
echo "========================================"
echo ""

# ── Java 21 ──────────────────────────────────────────────────
if ! java -version 2>&1 | grep -qE '"(21|22|23|24)'; then
    echo "[INFO] Installing Java 21..."
    sudo apt-get update -qq
    sudo apt-get install -y openjdk-21-jdk
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
fi
echo "[OK] $(java -version 2>&1 | head -1)"

# ── Python 3 + pip ───────────────────────────────────────────
if ! command -v python3 &>/dev/null; then
    sudo apt-get install -y python3 python3-pip
fi
echo "[OK] $(python3 --version)"

# ── LibreOffice (for Office file viewing) ────────────────────
if ! command -v libreoffice &>/dev/null && ! command -v soffice &>/dev/null; then
    echo "[INFO] Installing LibreOffice headless..."
    sudo apt-get install -y libreoffice-headless libreoffice-writer \
        libreoffice-calc libreoffice-impress --no-install-recommends -qq
fi
LO=$(command -v libreoffice || command -v soffice || echo "")
[ -n "$LO" ] && echo "[OK] LibreOffice: $($LO --version 2>/dev/null | head -1)" \
             || echo "[WARN] LibreOffice not found — Office files won't preview"

if ! python3 -c "import pypdf" 2>/dev/null; then
    echo "[INFO] Installing pypdf..."
    python3 -m pip install pypdf --break-system-packages -q \
        || python3 -m pip install pypdf --user -q
fi
echo "[OK] pypdf $(python3 -c 'import pypdf; print(pypdf.__version__)')"

# ── Tesseract OCR (for scanned PDF comparison) ───────────────
if ! command -v tesseract &>/dev/null; then
    echo "[INFO] Installing Tesseract OCR..."
    sudo apt-get install -y tesseract-ocr -qq
fi
echo "[OK] Tesseract: $(tesseract --version 2>&1 | head -1)"

# ── OCR libs (PyMuPDF replaces pdf2image — no Poppler needed) ───────────────────────────────────────
if ! python3 -c "import pytesseract, pypdfium2, PIL" 2>/dev/null; then
    echo "[INFO] Installing pytesseract, PyMuPDF, Pillow..."
    python3 -m pip install pytesseract pypdfium2 Pillow --break-system-packages -q \
        || python3 -m pip install pytesseract pypdfium2 Pillow --user -q
fi
echo "[OK] OCR Python libs ready"

# ── ezdxf ────────────────────────────────────────────────────
if ! python3 -c "import ezdxf" 2>/dev/null; then
    echo "[INFO] Installing ezdxf..."
    python3 -m pip install "ezdxf[draw]" --break-system-packages -q \
        || python3 -m pip install "ezdxf[draw]" --user -q
fi
echo "[OK] ezdxf $(python3 -c 'import ezdxf; print(ezdxf.__version__)')"

# ── ifcopenshell (IFC 3D viewing) ─────────────────────────────
if ! python3 -c "import ifcopenshell" 2>/dev/null; then
    echo "[INFO] Installing ifcopenshell..."
    python3 -m pip install ifcopenshell --break-system-packages -q \
        || python3 -m pip install ifcopenshell --user -q
fi
python3 -c "import ifcopenshell; print('[OK] ifcopenshell', ifcopenshell.version)" 2>/dev/null \
    || echo "[WARN] ifcopenshell not installed — IFC 3D viewing disabled"

# ── Gradle — find or install ─────────────────────────────────
GRADLE_CMD=""

# 1. Try ./gradlew (needs wrapper jar)
if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    chmod +x gradlew 2>/dev/null || true
    GRADLE_CMD="./gradlew"
    echo "[OK] Using Gradle wrapper"

# 2. Try downloading wrapper jar
elif [ -f "gradlew" ]; then
    echo "[INFO] Downloading Gradle wrapper jar..."
    mkdir -p gradle/wrapper
    if curl -fsSL --connect-timeout 10 \
        "https://raw.githubusercontent.com/gradle/gradle/v8.8.0/gradle/wrapper/gradle-wrapper.jar" \
        -o gradle/wrapper/gradle-wrapper.jar 2>/dev/null; then
        chmod +x gradlew
        GRADLE_CMD="./gradlew"
        echo "[OK] Gradle wrapper jar downloaded"
    elif wget -q --timeout=10 \
        "https://raw.githubusercontent.com/gradle/gradle/v8.8.0/gradle/wrapper/gradle-wrapper.jar" \
        -O gradle/wrapper/gradle-wrapper.jar 2>/dev/null; then
        chmod +x gradlew
        GRADLE_CMD="./gradlew"
        echo "[OK] Gradle wrapper jar downloaded (wget)"
    else
        echo "[WARN] Could not download wrapper jar — trying system gradle"
    fi
fi

# 3. Fall back to system gradle
if [ -z "$GRADLE_CMD" ]; then
    if ! command -v gradle &>/dev/null; then
        echo "[INFO] Installing Gradle..."
        # apt gradle is old (4.x) — install via SDKMAN or direct download
        if command -v sdk &>/dev/null; then
            sdk install gradle 8.8
        else
            # Use apt as last resort (will be old but functional for wrapper gen)
            sudo apt-get install -y gradle -qq
            gradle wrapper --gradle-version 8.8 2>/dev/null || true
            if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
                chmod +x gradlew
                GRADLE_CMD="./gradlew"
            fi
        fi
    fi
    if [ -z "$GRADLE_CMD" ]; then
        GRADLE_CMD="gradle"
        echo "[OK] Using system gradle: $(gradle --version 2>/dev/null | head -1)"
    fi
fi

# ── Start Python DXF converter ───────────────────────────────
echo ""
echo "[INFO] Starting DXF converter on port 5001..."
python3 converter/app.py &
CONVERTER_PID=$!
sleep 2
if kill -0 $CONVERTER_PID 2>/dev/null; then
    echo "[OK] Converter running (PID=$CONVERTER_PID)"
else
    echo "[WARN] Converter failed to start — DXF will use Java fallback"
    CONVERTER_PID=""
fi

# ── Start Spring Boot ────────────────────────────────────────
echo ""
echo "========================================"
echo " Open: http://localhost:8080"
echo " Login: admin / admin123"
echo " Press Ctrl+C to stop"
echo "========================================"
echo ""

cleanup() {
    echo ""
    echo "Stopping services..."
    [ -n "$CONVERTER_PID" ] && kill $CONVERTER_PID 2>/dev/null
    exit 0
}
trap cleanup INT TERM

$GRADLE_CMD bootRun

[ -n "$CONVERTER_PID" ] && kill $CONVERTER_PID 2>/dev/null
