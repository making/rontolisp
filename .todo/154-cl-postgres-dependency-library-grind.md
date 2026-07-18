# cl-postgres dependency libraries as REAL sources: remaining grind

Parent: `.todo/115` (cl-postgres driver). Design line: `.todo/147` (shims only
for portability-layer libraries; grow missing CL features instead). Goal state
for the `:depends-on ("md5" "split-sequence" "ironclad" "cl-base64" "uax-15")`
chain, plus the transitive `cl-ppcre` (via uax-15; cl-postgres itself has one
`cl-ppcre:scan` UUID check in data-types.lisp).

## Status (2026-07-18)

- `split-sequence` -- REAL, done (todo-054).
- `cl-base64` -- REAL, done (todo-085).
- `md5` -- REAL, DONE this session on the interpreter + JVM (`Md5E2eTest`,
  `examples/asdf/md5-demo.lisp`, RFC 1321 vectors + incremental API +
  `md5sum-string` UTF-8 via the flexi-streams shim's new `string-to-octets`).
  WASM excluded: the MD5 working state is unsigned 32-bit arithmetic beyond
  the `i31` fixnum range (same wall as int8/OID -- see the WASM-bignum idea
  below).
- `cl-ppcre` -- IN PROGRESS. Gates cleared so far: defpackage `:shadow`
  (real resolver support), `make-sequence`, `array-total-size-limit`,
  `define-condition :default-initargs`, defstruct docstrings + lite BOA
  constructors + struct names as defmethod specializers, defgeneric
  `(declare ...)` options. **Next gate (where the load now stops)**:
  `&environment` in a defmacro lambda list (`repetition-closures.lisp`
  `incf-after`, `api.lisp` `do-scans`). Lite plan: accept and bind the env
  var to nil. Immediately behind it: `get-setf-expansion` (incf-after
  destructures its 5 values via `multiple-value-bind`, so it must join the
  SYNTACTIC multiple-value producer set -- the mvb lowering needs to
  recognize it and destructure a compile-time 5-list; a lite expansion
  covering variable places + registered accessor conses should satisfy
  cl-ppcre) and 2-arg `(constantp form env)`. After those, resume the
  file-by-file iteration (`ql:quickload "cl-ppcre"`); 15 files / 7.4k lines,
  closures.lisp / convert.lisp / api.lisp are the heavy tail. Note
  `*standard-optimize-settings*` is read via `#.` in declares (works), and
  the library defines its own `defconstant`/`digit-char-p` through `:shadow`.
- `uax-15` -- blocked on cl-ppcre (`cl-ppcre:split` at load time), then
  needs: `asdf:find-system` + `asdf:system-source-directory` +
  `uiop:merge-pathnames*` + `make-pathname` (load-time data-file path
  resolution in precomputed-tables.lisp), and load-time
  `with-open-file :external-format :UTF-8` reads of `unicode-15-data/*.txt`
  (verify the keyword-value acceptance and per-line `read-line` perf over
  ~34k-line UnicodeData.txt). Loop shapes look supported (destructuring
  `for (a b c) in` works -- md5 exercised it).
- `ironclad` -- real loading judged INFEASIBLE under the current
  architecture: `ironclad.asd` is executable code (defclass on
  cl-source-file, a defmacro generating defsystems, uiop/format at parse
  time), which contradicts the mini-ASDF "parse `.asd` as plain data"
  invariant (`.kb/asdf.md`); the 129-file source tree also carries per-impl
  vops/MOP. Unless the ASDF subset grows real evaluation, the JDK-backed
  crypto shim strategy (frozen `cl-postgres-wip` branch, M2) remains the
  route -- consistent with `.todo/147` only if treated as a
  "cannot support rontolisp from its side" case; revisit when scram.lisp
  becomes the active gate.

## Related infrastructure idea (not scoped here)

WASM unsigned-32/bignum arithmetic: md5 (and int8/OID values in cl-postgres)
need exact integers past `i31`. Today they silently wrap into negative i31
values on the WASM backends. A boxed-i64 (or double-backed) overflow path
would unlock md5 on WASM and remove the `Md5E2eTest` backend exclusion.
