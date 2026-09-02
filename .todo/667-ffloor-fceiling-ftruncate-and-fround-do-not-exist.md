# `ffloor`, `fceiling`, `ftruncate` and `fround` do not exist

Difficulty: Low

Found while closing todo-660 (the exact float quotient, 2026-09-02): that item's brief
listed the eight CLHS rounders, and a `grep -ri ffloor src/ doc/ .kb/` hits NOTHING. Only
`floor`, `ceiling`, `truncate` and `round` are implemented; the four float-returning twins
are simply missing, on every backend and from the reference docs.

CLHS defines them as the same operation with a FLOAT quotient: `(ffloor number
&optional divisor)` returns `(float (floor number divisor))` as the primary value and the
same remainder as the second. The float format follows the argument (a rontolisp float is
always a double, so there is one format to answer). Over an integer argument the quotient
is still a float: `(ffloor 7 2)` is `3.0` and `1`, where `(floor 7 2)` is `3` and `1`.

SBCL, for reference (2026-09-02):

```
(ffloor 1d300)      => 1.0d300
(ffloor 1d300 7.0)  => 1.4285714285714286d299, 0.0d0
(ffloor 7 2)        => 3.0, 1
```

Note the quotient of `(ffloor 1d300 7.0)` is a FLOAT, so the exactness todo-660 bought for
the integer-returning family does not carry over to the primary value -- but the REMAINDER
is the same quantity, and rontolisp's is the exact one (`1.0` here, not SBCL's `0.0`; see
`.kb/linalg-simd.md`, "mod/rem", for why the two disagree and why rontolisp's reading is
the exact one).

## What to do

Add the four as MACROS lowering to the existing operators, which is what makes them free
on all five backends at once: the quotient is `(float (op number divisor))` and the second
value is whatever `op` already answers, so `LispMacroExpander` can lower them the way the
floor family's own two-argument form already lowers -- including the multiple-value
producer arm in `lowerMvProducer`, which is where the shared remainder formula lives.

Per the CLAUDE.md checklist for a macro: `LispNames` + `PackageRegistry.CL_SYMBOLS`, the
`LispEvaluator.evalCons` case, the `Jvm`/`Wasm` `ExprCompiler` cases, an `Environment`
registration plus a `BuiltinFunctionWrappers` entry so `#'ffloor` works, doc pages under
`reference/functions/` in `doc/en` and `doc/ja` with `_catalog.yaml` entries and a row in
`cl.md`, and a `ci-spec.yaml` case.

## Acceptance

- All four operators, one- and two-argument, single-value and multiple-value, on the
  interpreter, the JVM, wasm-GC and the component.
- `(ffloor 7 2)` is `3.0` and `1`; the second value is the same one the integer-returning
  operator answers, at every magnitude.
- `#'ffloor` is a first-class function value (the `BuiltinFunctionWrappers` half).
