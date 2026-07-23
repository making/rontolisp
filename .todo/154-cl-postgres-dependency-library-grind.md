# cl-postgres dependency libraries as REAL sources: remaining grind

Parent: `.todo/115` (cl-postgres driver). Design line: `.todo/147` (shims only
for portability-layer libraries; grow missing CL features instead). Chain:
`:depends-on ("md5" "split-sequence" "ironclad" "cl-base64" "uax-15")`, plus
the transitive `cl-ppcre` (via uax-15; cl-postgres itself has one
`cl-ppcre:scan` UUID check in data-types.lisp).

## Status (2026-07-23)

- `split-sequence` -- REAL, all 4 backends (todo-054).
- `cl-base64` -- REAL, all 4 backends (todo-085).
- `cl-ppcre` v2.1.2 -- REAL, all 4 backends (`ClPpcreE2eTest`).
- `uax-15` v0.1.3 -- REAL, all 4 backends (`Uax15E2eTest`). Last blocker
  cleared by `.todo/159`: WASM string model widened to UTF-8 byte encoding
  with three shared walking helpers (`_str_char_count` /
  `_str_char_at` / `_str_char_byte_offset`) so non-BMP scratch values
  survive `unicode-string` round-trips; component mem module now grows to
  the core module's data-segment needs so the 2.7MB UnicodeData tables
  don't trap at instantiation.
- `md5` -- REAL on interpreter + JVM (`Md5E2eTest`,
  `examples/asdf/md5-demo.lisp`). WASM excluded: MD5 working state is
  unsigned 32-bit arithmetic beyond the `i31` fixnum range. See
  "WASM unsigned-32 / bignum" below.
- `ironclad` -- REAL loading judged INFEASIBLE: `ironclad.asd` is
  executable code (defclass on cl-source-file, a defmacro generating
  defsystems, uiop/format at parse time), contradicting the mini-ASDF
  "parse `.asd` as plain data" invariant (`.kb/asdf.md`); 129-file source
  tree also carries per-impl vops/MOP. JDK-backed crypto shim strategy
  (frozen `cl-postgres-wip` branch, M2) remains the route unless the ASDF
  subset grows real evaluation. Revisit when scram.lisp becomes the
  active gate.

## Remaining

- **WASM unsigned-32 / bignum arithmetic**: md5 (and int8/OID values in
  cl-postgres) need exact integers past `i31`. Today they silently wrap
  into negative i31 values on the WASM backends. A boxed-i64 (or
  double-backed) overflow path would unlock md5 on WASM and remove the
  `Md5E2eTest` backend exclusion.
- **cl-postgres driver itself** (`.todo/115`): with the dependency chain
  now REAL modulo md5-on-WASM and ironclad, the next gate is the driver's
  own surface (see the parent todo for M2/M4/M5 status on the frozen
  `cl-postgres-wip` branch).
