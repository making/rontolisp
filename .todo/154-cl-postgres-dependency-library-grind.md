# cl-postgres dependency libraries as REAL sources: remaining grind

Parent: `.todo/115` (cl-postgres driver). Design line: `.todo/147` (shims only
for portability-layer libraries; grow missing CL features instead). Chain:
`:depends-on ("md5" "split-sequence" "ironclad" "cl-base64" "uax-15")`, plus
the transitive `cl-ppcre` (via uax-15; cl-postgres itself has one
`cl-ppcre:scan` UUID check in data-types.lisp).

## Status (2026-07-25)

- `split-sequence` -- REAL, all 4 backends (todo-054).
- `cl-base64` -- REAL, all 4 backends (todo-085).
- `cl-ppcre` v2.1.2 -- REAL, all 4 backends (`ClPpcreE2eTest`).
- `uax-15` v0.1.3 -- REAL, all 4 backends (`Uax15E2eTest`).
- `md5` -- REAL, ALL 4 BACKENDS (`Md5E2eTest`,
  `examples/asdf/md5-demo.lisp`). The WASM exclusion is RETIRED: the GC
  backend grew a boxed exact-integer path (`TYPE_BIGNUM` `{i64}`,
  `.kb/wasm-bignum.md`) -- arithmetic/bitwise/compare/print/read are exact
  through the signed 64-bit range with i31-normalization, which covers the
  unsigned 32-bit MD5 working state and the int8/OID values below.
- `ironclad` -- REAL loading judged INFEASIBLE: `ironclad.asd` is
  executable code (defclass on cl-source-file, a defmacro generating
  defsystems, uiop/format at parse time), contradicting the mini-ASDF
  "parse `.asd` as plain data" invariant (`.kb/asdf.md`); 129-file source
  tree also carries per-impl vops/MOP. JDK-backed crypto shim strategy
  (frozen `cl-postgres-wip` branch, M2) remains the route unless the ASDF
  subset grows real evaluation. Revisit when scram.lisp becomes the
  active gate.

## Remaining

- **WASM unsigned-32 / bignum arithmetic**: DONE 2026-07-25. The GC backend
  boxes exact integers past `i31` as `TYPE_BIGNUM` `{i64}` structs
  (`.kb/wasm-bignum.md`); the `Md5E2eTest` backend exclusion is removed and
  a `ci-spec.yaml` case (`exact-integers-beyond-the-i31-fixnum-range`) pins
  the surface on all four backends. Residual follow-up opportunities (none
  gate cl-postgres):
  - Boundary/time float representation: a wide integer crossing a
    `wasm-export`/wit-import boundary, and `get-universal-time` etc., still
    widen to a FLOAT (the pre-bignum "settled representation"). The reason
    (no wide integer type) no longer holds; retiring it would make
    `integerp` on those values answer like the other backends. See the
    re-evaluation trigger in `.kb/time-environment-builtins.md`.
  - `rontolisp:json-parse`'s ">9 digits becomes a float" rule (json.lisp)
    was motivated by the i31 cap and could now keep up to-64-bit integers
    exact (the jzon-compat divergence noted in its doc page).
  - Ratio components stay i32: an UNEVEN `/` of a bignum wraps its ratio
    components (even division is exact). `gcd`/`lcm`/`random` stay
    i31-range.
- **cl-postgres driver itself** (`.todo/115`): the dependency chain is now
  REAL modulo ironclad; int8/OID values fit the new WASM i64 path. The next
  gate is the driver's own surface (see the parent todo for M2/M4/M5 status
  on the frozen `cl-postgres-wip` branch).
