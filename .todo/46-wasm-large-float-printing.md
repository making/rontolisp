# WASM cannot print floats with magnitude >= 2^31

Discovered 2026-07-03 while adding `rontolisp:json-parse` (a 13-digit JSON
integer parses to a float on every backend, but printing it traps on WASM).
Reproduces on plain develop without JSON:

```lisp
(print (* 1.5 (expt 10.0 12)))
; interpreter/JVM: 1.5E12
; WASM (Preview 1 and component): wasm trap: integer overflow
```

The value itself is correct (arithmetic and comparisons work); only
`print`/`princ`/`princ-to-string` trap, because the float formatter extracts
the integer part through an `i32` conversion. Affects `rontolisp:json-parse`
(printing wide-integer-derived floats) and `rontolisp:json-stringify`
(serializing them), documented in both reference pages and the WASM guide.

Fix sketch: in the WASM float-to-string runtime, either extract the integer
part via `i64` (magnitudes up to 2^63) or switch to exponent normalization
(divide by 10 until the mantissa fits) before digit extraction, matching the
`1.5E12` shape the JVM/interpreter produce. Add ci-spec cases printing
`1.5e12` and `-2.5e15` once fixed.
