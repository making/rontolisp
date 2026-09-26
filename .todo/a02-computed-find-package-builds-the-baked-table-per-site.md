# A computed find-package builds the baked package table at every site

Difficulty: Medium

`LispMacroExpander.expandRuntimeFindPackage` lowers `(find-package p)` with a computed
designator to an `assoc` over a quoted table of every package, built fresh per call site: a
new datum each time, so the quote memo (`.kb/quoted-data.md`, keyed by datum identity) gives
every site its own `_qd$N` field and its own lazy builder. Measured 2026-09-26 (JVM, the
baked table of a plain program): 2,489 B of `_top$0` per additional site
(`(defvar *p* :cl) (let ((p *p*)) (print (find-package p)) ...)`), WASM ~1.6 KB per site.
Runtime-package programs pay it again inside each `runtimeMemberLookup` fallback.

`.todo/997` moved the computed find-symbol / intern guard into the `%symbol-in-package`
prelude defun, so those sites no longer pay it; a bare computed `find-package`, uiop's
`find-package*`, and the `package-use-list` / `package-used-by-list` lowerings still do.
Direction: one shared lookup per program (a helper defun, or the table datum shared by
identity plus an out-of-line builder), then re-measure a library-heavy program (mito probe).
