# 8. Run the conversion and scanning toolchain out of process

- **Status:** Accepted
- **Date:** 2026-08-27 (recording a decision taken earlier)

## Context

The product converts DWG, DXF, Office documents and scanned PDFs, and scans
every upload for malware. The best tools for those jobs — LibreDWG,
LibreOffice, Tesseract, ClamAV — are all GPL or MPL licensed and are all
heavyweight native parsers.

Two independent constraints point the same way.

**Licence.** The product is proprietary. Linking or embedding a GPL library
creates a combined work and would put the whole product under the GPL.

**Security.** Office, PDF and CAD parsers are among the richest sources of
memory-corruption CVEs in existence, and they run against files an attacker
chooses. Parsing untrusted input in the application process means a parser
bug is a compromise of the application, its database credentials, and its
tenant context.

## Options

**Link native libraries via JNI or the FFM API.** Fastest, no process
boundary. Fails the licence test outright for the GPL tools and puts a
hostile parser inside the application's address space.

**Pure-Java parsers only.** PDFBox covers PDF, but there is no adequate
pure-Java DWG or Office converter, and it does not solve the parser-CVE
problem, only relocates it into the JVM.

**Separate processes and services.** A conversion sidecar invoked over HTTP,
ClamAV over a socket. Costs a network hop, a deployment component, and
serialisation.

## Decision

Everything untrusted is parsed out of process.

| Tool | Licence | Invocation |
|---|---|---|
| ClamAV | GPL-2.0 | INSTREAM over a TCP socket |
| LibreOffice | MPL-2.0 | `soffice --headless` subprocess |
| LibreDWG `dwg2dxf` | GPL-3.0 | subprocess |
| Tesseract | Apache-2.0 | subprocess |

None is linked, embedded, or bundled into the application artifact. Mere
aggregation, not a combined work.

## Consequences

- The product's proprietary licence is unaffected by the GPL tools, and a
  parser crash or exploit is contained in a process that holds no database
  credentials and no tenant context.
- The process boundary is load-bearing for both reasons. Moving any of these
  in-process for performance would simultaneously break the licence position
  and remove the sandbox — it is not a tuning decision available to us.
- **An obligation is outstanding.** `dwg2dxf` is GPL-3.0 and is compiled into
  the converter image. Invoking it as a subprocess keeps our code clear, but
  *shipping the image distributes the binary*, and GPL-3.0 §6 then requires
  that recipients be offered the corresponding source. That is not
  discharged, and it blocks distribution of the converter image. Options and
  a recommendation are in `docs/licences.md` §4.1.
- The conversion sidecar is optional at runtime. When it is unreachable the
  Java DXF parser handles DXF, and the failure is logged as an expected
  condition rather than an incident.
- The sandbox is currently a process boundary, not a resource-limited,
  network-isolated worker with a timeout. Tightening it is outstanding work.
