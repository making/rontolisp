# lack-request / lack-response + middleware ecosystem

Difficulty: 低〜中 for the work REMAINING IN THIS TODO (shim widening +
.asd front-end + pins + final assembly). The heavyweight substrate items
found by the survey are split out — this todo is blocked on them for the
lack-request half:

- `.todo/232` CLOS multiple inheritance + setf methods + setf
  symbol-function/fdefinition places (高) — DONE 2026-08-02 (`.kb/clos.md`,
  `.kb/symbol-runtime-api.md`); the remaining Gray-stream half is
  `.todo/235` (中〜高) — gates circular-streams,
  and therefore lack-request / -session / -csrf
- `.todo/233` `#.` read-time eval (中) — gates fast-http
- `.todo/234` `:method-combination progn` (中) — gates yason (http-body's
  JSON dep)

Part of the Clack milestone `.todo/223`, STRETCH. Survey COMPLETE
(2026-08-02, full patch-probe on the clack spike method: patch in the
quicklisp cache until each system loads, then discard the patched releases
— all patches thrown away, this file + the split todos are the record).
The original "multi-library lineage grind, 高" estimate is obsolete: quri,
local-time, alexandria, babel, ironclad, cl-ppcre etc. landed via other
work.

## Loads verbatim TODAY (probed, no change needed — just add pins)

lack-response, lack-util, lack-middleware-accesslog, lack-middleware-auth-basic,
lack-middleware-mount, lack-middleware-static, lack-app-file, trivial-mimes,
trivial-rfc-1123, xsubseq, proc-parse, cl-utilities.

(lack-middleware-when not probed but deps are lack-util only.)

## Work remaining in THIS todo

The lack-request chain is smart-buffer -> fast-http -> http-body +
circular-streams; lack-middleware-session / -csrf are blocked ONLY by
lack-request. With the split todos done plus the items below, every one of
them loaded during the probe.

1. **uiop shim widening (低)** — smart-buffer needs
   `ensure-directory-pathname`, `default-temporary-directory`,
   `with-temporary-file` (a macro; on the LIVE disk-spill path, needs a real
   implementation), `delete-file-if-exists`. Widen in PackageRegistry/Java
   as usual (a runtime Lisp-side `export` cannot patch it — see the
   resolution quirk noted in `.todo/232`).
2. **flexi-streams shim widening (低〜中)** — nickname `FLEX` missing
   (smart-buffer/http-body spell `flex:`); `make-in-memory-input-stream`
   (smart-buffer `finalize-buffer`, live path — real octet in-memory stream
   needed); `vector-stream` class + internal `vector-stream-vector` accessor
   (http-body `slurp-stream` fast path — can degrade to the read-sequence
   branch if typep on the class can answer nil).
3. **trivial-gray-streams shim widening (中)** — moved to `.todo/235`
   (Gray binary/input stream classes, input generics,
   `stream-file-position`), which owns the core-protocol half too.
4. **.asd front-end (低)** — fast-io.asd opens with a top-level
   `(eval-when ... (pushnew :fast-io *features*))`; the front-end accepts
   only DEFSYSTEM/DEFPACKAGE/IN-PACKAGE/DEFPARAMETER/REGISTER-SYSTEM-PACKAGES.
5. **Pins/E2E (低)** — pin the already-loading middleware set now (it needs
   nothing), and the full lack-request/session/csrf chain once the split
   todos land. Loading was the only thing probed: runtime behavior
   (multipart parsing through smart-buffer's spill path, in-memory streams,
   session store) still needs the usual all-four-backends verification.

## Non-blockers discovered (corrections to the original survey)

- http-body's JSON dep is **yason**, not jonathan — far lighter
  (alexandria + trivial-gray-streams only; no cl-annot/cl-syntax lineage).
- **static-vectors / cffi are NOT needed**: fast-io gates them behind
  `#+fast-io-sv` (pushed only on sbcl/ccl/cmucl/ecl/lispworks/allegro).
  static-vectors' .asd contains a bare `(error "...")` on unknown
  implementations but is never reached on rontolisp.
- ironclad already loads (lack-util probe passed) — session/csrf's crypto
  dep is a non-issue at load time.

## Framework tier (evaluate later, NOT part of this todo)

- myway: blocked in dep map-set on defstruct `:print-function` (低).
- ningle: blocked on defsystem `:class` option (package-inferred style)
  in the .asd front-end (中?).
