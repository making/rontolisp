# j80. wasm: `the` around an integer assignment takes the loop off its unboxed path

Difficulty: Medium

Found by the j68 spike and measured again 2026-11-17 (Linux x86-64, wasmtime 49, best of
three, precompiled): the 30,000,000-step loop

```lisp
(let ((h 0) (i 0))
  (while (< i 30000000)
    (setq h (- (logand (+ (+ (* 31 h) (rem i 7)) 2147483648) 4294967295) 2147483648))
    (setq i (+ i 1)))
  (print h))
```

runs 0.11 s on wasm and 0.20 s on the JVM; with `(declare (type fixnum h i))` alone 0.09 /
0.18; with each assigned value wrapped in `(the fixnum ..)` 0.53 / 0.18. So `the` costs wasm
5x and the JVM nothing. `the` expands to its value form (`.kb/declarations-type-checks.md`),
but the wasm decisions taken before the expansion read the raw form: likely
`WasmIntFusionCompiler.isRawAssignShaped` (the dual-representation local's eligibility,
`.kb/wasm-unboxed-locals.md`) does not look through `the`, where `valueRange` already does
(`case LispNames.THE`). Check every pre-expansion classifier of the wasm int path
(`isRawAssignShaped`, the counted-loop recognizer, `WasmLeafVersioning`) against the JVM's
`JvmIntFusionCompiler.isRawAssignShaped`.

A declared program must be at least as fast as the same program undeclared, on every
backend. Pin it with a fuel or size assertion in `WasmLispCompilerIntegrationTest` (a
`(the fixnum ..)` assignment unboxes the local as the bare one does).
