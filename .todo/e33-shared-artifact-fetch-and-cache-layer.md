# e33. One artifact fetch-and-cache layer under DistClient, Maven and git

Difficulty: Medium

Foundation for `deps.edn` (`e34`..`e43`). Today `eval/DistClient` is the only downloader and its
transport is private and Quicklisp-shaped: `httpGet` (no timeout, `ql:quickload:` wording),
`extractTarGz`, `safeResolve`, the atomic staging-then-rename install (`.kb/dists.md`,
"Identity and cache"). A Maven resolver (`e34`) and a git fetcher (`e36`) need the same
pieces; a second and third copy is the wrong shape.

## Gaps

1. No shared layer: extract HTTP GET, atomic install, safe path resolution and the cache root
   (`RONTOLISP_DIST_HOME` / `~/.rontolisp`, read only in `createDefault`) into one component
   DistClient consumes. Package placement follows `.kb/architecture.md` (language-independent
   if it imports no rontolisp package).
2. Checksums are never verified: `releases.txt` carries `md5`/`sha1` and `parseReleases` drops
   them, so a corrupted tarball is installed forever (existence IS the installed mark). This is a
   CL defect on its own: failing test first (`DistClientTest`, a release whose bytes do not
   match its sha1 must not install).
3. No timeout on the `HttpClient`: a stalled server hangs the compile.
4. No zip extraction: a jar is a zip. Same atomic-install and path-escape rules as tar.gz.
5. Web profile: `Target_DistClient` substitutes `createDefault`; the new layer needs the same
   browser refusal so every consumer fails at its own call site.

## Plan

1. Read `.kb/dists.md`, `.kb/architecture.md`, `DistClient`.
2. Test for gap 2 (red), then the extraction with DistClient as first consumer; gaps 2-5.
3. Update `.kb/dists.md` (verification, timeout, the shared layer); user docs only if a
   user-visible refusal changes.
