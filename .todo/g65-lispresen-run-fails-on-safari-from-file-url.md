# g65: lispresen's Run fails in Safari when the deck is opened as a file

Difficulty: Medium

Opening a deck's HTML through `file://` in Safari (26.3.1, 2026-10-23), pressing Run on a
runnable code block shows "Failed"; the same deck served over HTTP runs every block, and
Chrome runs it from `file://`. The runtime loading (`:runtime`, `--embed`) is the likely
seam: Safari treats each `file://` URL as a unique origin and refuses fetch/XHR/Worker or
WebAssembly streaming from it.

## Goal

Find which request Safari refuses and make Run work from `file://` in Safari for every
`:runtime` mode where that is possible (e.g. an embedded runtime instantiated from bytes
already in the page). Where a mode cannot work from `file://`, the player says so
specifically instead of "Failed", and README/DESIGN state it.
