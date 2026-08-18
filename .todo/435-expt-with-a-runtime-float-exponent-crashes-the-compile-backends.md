# 435. `expt` with a runtime float exponent crashes the compile backends

Difficulty: Medium

`Jvm/WasmExptCompiler` pick the emission by a STATIC literal scan
(`hasDoubleLiteral`): a double literal anywhere in the call takes `Math.pow`,
everything else takes the exact-integer `_pow` path, which unboxes the exponent
as an integer. A float that arrives at run time -- from a function call, a
variable, a `float` coercion the scan cannot see -- hits the integer path and
dies:

```lisp
(defun give0 () 0.0)
(print (expt 10 (give0)))          ; JVM: ok in isolation (single call site?)
(print (* 1.5 (expt 10 (give0)))) ; JVM: ClassCastException Double->Long
                                   ; WASM (both): unreachable trap
```

The interpreter answers `1.0` / `1.5`. Verified pre-existing on a clean
`develop` worktree (2026-08-18) -- unrelated to the progv work that exposed it.

## The consumer that found it

cl-json's `parse-number` (`src/decoder.lisp`) computes
`(* (floatify significand) (expt 10 (floatify exponent)))`, so DECODING ANY
FLOAT via `json:decode-json-from-string` crashes on all three compile backends
while integers/strings/booleans/aggregates decode fine (pinned by
`ClJsonE2eTest`, which deliberately avoids float inputs and names this item).

## Shape of a fix

The integer `_pow` path needs a runtime type check on the exponent (and
probably the base): a non-integer falls over to the `Math.pow` /
`_math_pow`-style double path, mirroring how the arithmetic fast paths bail to
their generic arms. All three compile backends (`JvmExptCompiler`,
`WasmExprCompiler`'s expt case, and whatever `--no-gc` does with expt), plus a
`ci-spec.yaml` case and a float-decoding line added to `ClJsonE2eTest`'s
exercise once it passes. `(expt 10 -1)` must stay `1/10` (exact ratio), and a
double LITERAL operand must keep today's emission byte-identically.
