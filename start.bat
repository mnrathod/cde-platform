@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ========================================
echo  CDE Platform - Starting services
echo ========================================
echo.

REM ── Java ─────────────────────────────────
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java not found.
    echo         Install JDK 21: winget install EclipseAdoptium.Temurin.21.JDK
    pause
    exit /b 1
)
echo [OK] Java found

REM ── Gradle wrapper jar ───────────────────
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [INFO] Downloading Gradle wrapper jar...
    mkdir "gradle\wrapper" >nul 2>&1
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.8.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'" >nul 2>&1
    if exist "gradle\wrapper\gradle-wrapper.jar" (
        echo [OK] Gradle wrapper downloaded
    ) else (
        echo [ERROR] Could not download Gradle wrapper jar.
        echo         Download from: https://raw.githubusercontent.com/gradle/gradle/v8.8.0/gradle/wrapper/gradle-wrapper.jar
        echo         Save to: gradle\wrapper\gradle-wrapper.jar
        pause
        exit /b 1
    )
) else (
    echo [OK] Gradle wrapper ready
)

REM ── Python ───────────────────────────────
set PYTHON=
for %%P in (python py python3) do (
    if not defined PYTHON (
        %%P --version >nul 2>&1
        if !errorlevel! == 0 set PYTHON=%%P
    )
)
if not defined PYTHON (
    for %%D in (
        "%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
        "%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
        "%LOCALAPPDATA%\Programs\Python\Python311\python.exe"
        "%LOCALAPPDATA%\Programs\Python\Python310\python.exe"
        "C:\Python313\python.exe"
        "C:\Python312\python.exe"
    ) do if not defined PYTHON if exist %%D set PYTHON=%%D
)
if defined PYTHON (
    echo [OK] Python: %PYTHON%
) else (
    echo [WARN] Python not found - file viewing disabled
)

REM ── pip packages ─────────────────────────
if defined PYTHON (
    %PYTHON% -c "import ezdxf" >nul 2>&1
    if !errorlevel! neq 0 (
        echo [INFO] Installing ezdxf...
        %PYTHON% -m pip install "ezdxf[draw]" --quiet
    )
    echo [OK] ezdxf ready

    %PYTHON% -c "import ifcopenshell" >nul 2>&1
    if !errorlevel! neq 0 (
        echo [INFO] Installing ifcopenshell...
        %PYTHON% -m pip install ifcopenshell --quiet
    )
    echo [OK] ifcopenshell ready

    %PYTHON% -c "import pypdf" >nul 2>&1
    if !errorlevel! neq 0 (
        echo [INFO] Installing pypdf...
        %PYTHON% -m pip install pypdf --quiet
    )
    echo [OK] pypdf ready

    %PYTHON% -c "import pdfplumber" >nul 2>&1
    if !errorlevel! neq 0 (
        echo [INFO] Installing pdfplumber...
        %PYTHON% -m pip install pdfplumber --quiet
    )
    echo [OK] pdfplumber ready

    %PYTHON% -c "import pypdfium2" >nul 2>&1
    if !errorlevel! neq 0 (
        echo [INFO] Installing pypdfium2...
        %PYTHON% -m pip install pypdfium2 --quiet
    )
    echo [OK] pypdfium2 ready

    %PYTHON% -c "import pytesseract" >nul 2>&1
    if !errorlevel! neq 0 (
        echo [INFO] Installing pytesseract...
        %PYTHON% -m pip install pytesseract Pillow --quiet
    )
    echo [OK] pytesseract ready
)

REM ── Tesseract OCR ────────────────────────
set TESS_EXE=
if exist "C:\Program Files\Tesseract-OCR\tesseract.exe" set TESS_EXE=C:\Program Files\Tesseract-OCR\tesseract.exe
if not defined TESS_EXE (
    where tesseract >nul 2>&1
    if !errorlevel! == 0 set TESS_EXE=tesseract
)
if defined TESS_EXE (
    echo [OK] Tesseract found
) else (
    echo [INFO] Installing Tesseract via winget...
    winget install --id UB-Mannheim.TesseractOCR --silent --accept-package-agreements --accept-source-agreements
    if exist "C:\Program Files\Tesseract-OCR\tesseract.exe" (
        set "PATH=%PATH%;C:\Program Files\Tesseract-OCR"
        echo [OK] Tesseract installed
    ) else (
        echo [WARN] Tesseract not installed - scanned PDF comparison disabled
        echo        Manual install: https://github.com/UB-Mannheim/tesseract/wiki
    )
)

REM ── LibreOffice ───────────────────────────
set LO_EXE=
for /d %%D in ("C:\Program Files\LibreOffice*") do (
    if not defined LO_EXE if exist "%%D\program\soffice.exe" set LO_EXE=%%D\program\soffice.exe
)
for /d %%D in ("C:\Program Files (x86)\LibreOffice*") do (
    if not defined LO_EXE if exist "%%D\program\soffice.exe" set LO_EXE=%%D\program\soffice.exe
)
if defined LO_EXE (
    echo [OK] LibreOffice: %LO_EXE%
) else (
    echo [INFO] Installing LibreOffice via winget...
    winget install --id TheDocumentFoundation.LibreOffice --silent --accept-package-agreements --accept-source-agreements >nul 2>&1
    if !errorlevel! == 0 (
        echo [OK] LibreOffice installed
    ) else (
        echo [WARN] LibreOffice not installed - Office/PDF conversion disabled
        echo        Install from: https://www.libreoffice.org/download
    )
)

REM ── C:\Temp ───────────────────────────────
if not exist "C:\Temp" mkdir "C:\Temp" >nul 2>&1

REM ── Start converter ──────────────────────
if defined PYTHON (
    echo.
    echo [INFO] Starting converter on port 5001...
    start "CDE-Converter" /min cmd /c "%PYTHON% converter\app.py & pause"
    timeout /t 3 /nobreak >nul
    %PYTHON% -c "import urllib.request; urllib.request.urlopen('http://localhost:5001/health',timeout=3)" >nul 2>&1
    if !errorlevel! == 0 (
        echo [OK] Converter running
    ) else (
        echo [WARN] Converter not responding - using Java fallback
    )
)

REM ── Start Spring Boot ─────────────────────
echo.
echo ========================================
echo  Open:  http://localhost:8080
echo  Login: admin / admin123
echo  Ctrl+C to stop
echo ========================================
echo.
call gradlew.bat bootRun
endlocal
