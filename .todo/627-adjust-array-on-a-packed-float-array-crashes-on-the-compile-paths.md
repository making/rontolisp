# `adjust-array` on a PACKED FLOAT array crashes on the compile paths

Difficulty: Low

Found 2026-09-02 while doing `.todo/619`, which fixed the element type the
adjusted COPY carries and made an IMMUTABLE string a legal argument. This is the
last shape in that family the two halves still disagree about, and it is a
DIAGNOSTIC gap, not a semantic one: nobody adjusts a packed float array
successfully anywhere.

```lisp
(let ((a (make-array 3 :element-type 'double-float)))
  (adjust-array a 5))
;; interpreter:  ADJUST-ARRAY: not applicable to a packed float array
;; JVM:          class [F cannot be cast to class java.util.ArrayList
;; wasm (both):  wasm trap: cast failure
```

The packed INTEGER vector already answers the interpreter's text on all four
(`_ivRequireGeneral` on the JVM, the matching trap message on wasm, reached
through `%array-become`'s `emitRequireGeneralIfPacked`); the float array has no
such guard, so `expandAdjustArray`'s `%array-disp-target` reaches a
`checkcast ArrayList` / `castCellGet0` on a `float[]` / `TYPE_FARRAY` and dies
there. `(array-dimensions f)` on a packed float array works -- `compileDims` and
`_arrayDims` both open with the farray arm -- so the crash is the displacement
probe alone, i.e. the first primitive in the expansion that has no farray arm.

The fix is the integer vector's, one representation over: a require-general
guard at the head of the `adjust-array` expansion (or inside
`%array-disp-target`) that answers
`adjust-array: not applicable to a packed float array` for a `TYPE_FARRAY` /
`float[]`/`double[]` argument. Emit it only when the program can hold a packed
float array (`ctx.usesFloatArray` and the wasm equivalent), so a program without
one compiles to the same bytes -- `.todo/619` measured the whole family at
+0.06% on `zlib` and this must not add to that for a program that cannot trip it.

Pin on all four backends the way `.todo/619` did: a `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` trio asserting the same
message, and note it in `.kb/adjustable-arrays.md` where "What is still NOT
adjustable" records the divergence today.
