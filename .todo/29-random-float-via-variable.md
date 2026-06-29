# 29 - `random` miscompiles a float limit reaching it through a variable (JVM + WASM)

## Symptom

`(random limit)` where `limit` is a float that is NOT a compile-time literal (a
variable, parameter, or computed value with no float literal in the expression)
miscompiles on BOTH compiler backends; the interpreter is correct.

```lisp
(let ((x 5.0)) (floatp (random x)))   ; interpreter: T
```

- **JVM**: `java.lang.ClassCastException: class java.lang.Double cannot be cast to
  class java.lang.Long` (in `Rnd.main`).
- **WASM** (Preview 1 / component): the module traps at runtime ("failed to run
  main module").

A float *literal* limit (`(random 5.0)`), or any limit expression that textually
contains a float literal, works on every backend.

## Root cause

Same class as the abs/signum bug fixed for the JVM (former .todo/28): `random`
selects its integer vs float path with the purely *syntactic*
`hasDoubleLiteral` heuristic instead of the operand's runtime type.

- JVM `JvmRandomCompiler`: with no double literal it compiles the integer path,
  which `unboxLong` (CHECKCAST Long) on the limit; a `Double` at runtime throws
  `ClassCastException`. (`(long)(Math.random() * limit)`.)
- WASM `WasmRandomCompiler`: same compile-time split (see its class doc, "the
  integer and float paths are selected at compile time"); the integer path unboxes
  the limit as an i31, so a `TYPE_FLOAT` operand traps on the cast. Unlike
  `abs`/`signum`, WASM's `random` integer path does NOT route through the
  type-dispatching rat helpers, so WASM is broken here too (abs/signum were only
  broken on the JVM).

## Fix direction

Dispatch on the limit's runtime type, the way `mod`/`rem` (commit e7eee65) and
`abs`/`signum` (former .todo/28) now do.

- **JVM**: add a runtime `_random(Object limit)` numeric helper:
  `d = Math.random() * _dbl(limit); return (limit instanceof Double) ?
  Double.valueOf(d) : Long.valueOf((long) d)`. Route `JvmRandomCompiler`'s else
  branch through it (keep the compile-time double-literal fast path). Using
  `_dbl(limit)` for the multiply also makes the integer path robust to a
  BigInteger/ratio limit, which the current `unboxLong` path is not.
- **WASM**: `WasmRandomCompiler` needs a runtime type test (ref.test TYPE_FLOAT)
  selecting an f64 `random_get`-based path vs the i31 path, or a `_random` runtime
  helper mirroring the JVM one. The existing `random_get` import is available in
  both modes.

Per the bug-fix workflow: first add a failing cross-backend test (a float `random`
through a `let`-bound variable -- assert `floatp`/range, not the random value
itself -- plus a `ci-spec.yaml` case), then fix both backends, then confirm parity
with the interpreter on all four backends + the native image.

## Broader note

`hasDoubleLiteral` is the shared compile-time float-vs-int discriminator. Fixed so
far: `mod`/`rem` (e7eee65), `abs`/`signum` (former .todo/28). `min`/`max` are safe
(they go through `_cmp` and return an original boxed arg, no cast). `random` is the
remaining known-broken caller. Re-audit the full `hasDoubleLiteral` caller set
(JVM `JvmArithCompiler`/`JvmComparisonCompiler`/etc., and the WASM equivalents)
for any other op whose non-float path casts the operand.
