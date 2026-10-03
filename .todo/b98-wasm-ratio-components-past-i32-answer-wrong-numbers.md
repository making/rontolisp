# b98. WASM ratios with components past i32 answer wrong numbers

Difficulty: High

Measured 2026-10-03 (worktree of b85, interpreter/JVM correct, both wasm
backends wrong, no trap and no warning):

- `(print (/ 3000000000 7))` -> `-184995328` (should be `3000000000/7`).
- `(print (/ 30000000000000004 100000000000000000))` -> `110815915/130777088`
  (should be `7500000000000001/25000000000000000`).
- Scheme `(string->number "0.30000000000000004")` -> `0.8473649069170281`,
  `"3.141592653589793"` -> `-0.1112221416400128`, `(read (open-input-string
  "0.1234567890123"))` -> `1.4529484428720936`, `(exact->inexact
  30000000000000004/100000000000000000)` -> `-1.8894596149237803`;
  `(string->number "1e-300")` traps (`unreachable`). `%scheme-decimal` floats
  the exact ratio `mantissa * 10^(exponent-scale)`.
- Cause: `.kb/wasm-bignum.md`, "Ratio components stay i32": `_rat_new` takes
  two i32, `_rat_num`/`_rat_den` wrap a TYPE_BIGNUM to i32, and `_as_f64`'s
  ratio arm converts those i32. The KB calls it a deliberate limit with "a
  user-level `(/ big 3)`" as the reachable gap; the measurements above show the
  gap answers silently wrong numbers, including every Scheme decimal with more
  than ~9 significant digits.
- b85 sidestepped it for the Clojure run-time reader: `%clojure-rd-double`
  (`clojure.lisp`) builds a double from its IEEE bits over exact integer
  arithmetic, never through a ratio.

## Plan

- Decide between promoting ratio components to the big tier (`_rat_*` over
  `_big_*`, the `TYPE_RAT_NEW`/`TYPE_RAT_GET` sharing the KB names) and, at the
  least, trapping or signalling instead of wrapping. Measure the module-size cost
  on a ratio-free program (the type-test fold should keep it at zero).
- Independently, Scheme's inexact decimal path can take the IEEE-bits
  conversion (`%clojure-rd-double`'s algorithm, shared through the prelude rather
  than copied).

## Pin

- ci-spec / scheme spec: `(/ 3000000000 7)`, a 17-digit `string->number`, on all
  four backends; `WasmLispCompilerIntegrationTest` for the ratio runtime.
