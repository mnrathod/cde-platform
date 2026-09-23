"""
What the service recognises a file as, by extension or by magic bytes.

<p>These two tables were module-level constants in ``app.py`` and were the
only reason an extracted module would have had to import back into it, which
is a cycle. They are facts about file formats, they depend on nothing, and
several modules need them, so they live on their own at the bottom of the
import graph.
"""

# ── DWG format versions, by the six-byte signature at the head of the file ──
# The version matters to a reader because ODA needs to be told which one to
# convert from, and because a file too old for the installed converter fails
# with a message that means nothing unless the version is named.
DWG_VERSIONS = {
    "AC1015": "AutoCAD 2000",
    "AC1018": "AutoCAD 2004",
    "AC1021": "AutoCAD 2007",
    "AC1024": "AutoCAD 2010",
    "AC1027": "AutoCAD 2013",
    "AC1032": "AutoCAD 2018",
    "AC1035": "AutoCAD 2021",
    "AC1037": "AutoCAD 2023",
}

# ── Extensions LibreOffice can open and convert ──
# Not "Office formats" in the Microsoft sense: this is the set the conversion
# and text-extraction paths route through LibreOffice, which is why the
# OpenDocument, RTF and plain-text extensions are in here alongside the rest.
OFFICE_EXTS = {
    "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "odt", "ods", "odp", "rtf", "txt", "csv", "html", "htm",
}
