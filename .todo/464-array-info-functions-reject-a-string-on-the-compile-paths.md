# `array-dimensions` / `array-rank` / `array-total-size` / `adjustable-array-p` / `array-has-fill-pointer-p` reject a string on the compile paths

Difficulty: Medium

Found 2026-08-19 while doing `.todo/368` (`array-element-type` on a string). The
interpreter handles all five for a string, but BOTH compile paths trap:

```lisp
(array-dimensions "abc")
;; interpreter: (3)
;; JVM:         Unhandled condition: java.lang.String cannot be cast to java.util.ArrayList
;; WASM:        trap: cast failure
```

Same for `array-rank` / `array-total-size` / `array-has-fill-pointer-p`; `adjustable-array-p`
needs the same check. `array-rank`/`array-total-size`/`array-dimension` all lower through
`array-dimensions` (`LispMacroExpander.expandArrayRank/expandArrayTotalSize`), so fixing
`array-dimensions` fixes those three transitively; the other two need their own string arm.

The interpreter already has the string arms (the reference answers):
- `array-dimensions` -> `(capacity)` (`Environment` ARRAY_DIMENSIONS: `LispString` -> `new int[]{str.capacity()}`)
- `array-has-fill-pointer-p` -> `str.fillPointer() >= 0`
- `adjustable-array-p` -> `str.adjustable()`

The compile paths call a runtime helper with no string arm:
- `array-dimensions`: `JvmArrayCompiler.compileDims` / `WasmArrayCompiler.compileDims`
- `array-has-fill-pointer-p`: `JvmArrayCompiler.compileHasFillPointer` / `WasmArrayCompiler.compileHasFillPointer`
- `adjustable-array-p`: `JvmArrayCompiler.compileAdjustableArrayP` / `WasmArrayCompiler.compileAdjustableArrayP`

The string arm is not a plain `stringp` branch: a LITERAL string is a quote-framed
`java.lang.String` / `TYPE_STRING` (dims = stored length minus the two frame quotes, no fill
pointer, not adjustable), while a fill-pointered/adjustable string is a charvec
(`ArrayList` length-4 header / `TYPE_CELL` meta==1) whose dims/fill-pointer/adjustable live in
the header. So each arm must dispatch on the quote-frame vs. charvec shape (the same split
`stringp` uses -- `JvmStringpCompiler.emitStringpCheck` / `WasmStringpCompiler.emitStringpI32`),
and the charvec side reads the header fields.

Pin one case per backend (interpreter, JVM, WASM) plus a `ci-spec.yaml` line once fixed.
`vectorp`, `length`, `aref`, `elt` already accept a string on every backend and are the
model; `array-element-type` was fixed in the same pass (`.todo/368`).
