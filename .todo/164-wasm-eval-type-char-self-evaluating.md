# WASM runtime `_eval`: TYPE_CHAR is not treated as self-evaluating

A CHARACTER literal that reaches the WASM `_eval` runtime interpreter causes
`wasm trap: cast failure` on both `--component` and plain wasm-GC (P1)
compiles. Any form containing a `#\` literal that is walked by runtime `eval`
is affected:

    (eval '(eq #\A #\A))                    ; interp/JVM T, WASM trap
    (eval (list 'eq (list 'code-char 65) #\A))  ; interp/JVM T, WASM trap

`(eval '(eq 42 42))` and `(eval '(eq (code-char 65) (code-char 65)))` both
work on WASM — an integer is self-evaluating (i31), a `(code-char ...)` cons
is walked and evaluated. It's only the raw CHARACTER value (a `TYPE_CHAR`
struct) reaching `_eval` that trips.

## Root cause

`WasmEvalRuntimeBuilder.buildEvalBody` clause 2 enumerates the self-evaluating
heap types (`WasmEvalRuntimeBuilder.java:513`):

    for (int heapType : new int[] { Type.I31.code(), WasmLispCompiler.TYPE_RATIO,
            WasmLispCompiler.TYPE_FLOAT, WasmLispCompiler.TYPE_CLOSURE }) {
        ...
    }

TYPE_CHAR is missing from the list. A CHARACTER form therefore falls through
the atom guards into the cons-application branch, where `emitCarOf` casts to
`TYPE_CONS` and traps.

Same pattern as todo 153 -- CHARACTER was widened to a first-class value on
every backend, but the WASM `_eval` self-eval enumeration was never taught
about it.

## Plan

Add `WasmLispCompiler.TYPE_CHAR` to the heap-type array in
`WasmEvalRuntimeBuilder.buildEvalBody` clause 2. A character is a leaf value
with no environment resolution, exactly like a float or ratio.

## Verification

- New WASM integration test: `(eval '(eq #\A #\A))` -> `T` on both P1 and
  component.
- Extend ci-spec `eq-on-characters-by-code-point` (or a new case) with the
  eval-through variant so it runs on every backend byte-identically.
- Regression sweep of the existing `_eval` tests to confirm the new clause
  doesn't misdispatch any non-CHAR form.

## Discovered by

Exhaustive verification of todo 162 (`(eq #\A #\A)` byte-identity across the
four backends). Todo 162 was in scope for the WASM compile path only; this
runtime-eval gap is a separate bug in the same neighbourhood.
