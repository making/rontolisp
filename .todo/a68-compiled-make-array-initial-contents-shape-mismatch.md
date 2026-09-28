# Compiled make-array :initial-contents does not check the contents' shape

Difficulty: Medium

Measured 2026-09-28 on all four backends. The interpreter's native `make-array` rejects contents
whose shape does not match the dimensions:

```lisp
(make-array 3 :initial-contents (list 1 2))
;; interpreter: MAKE-ARRAY :initial-contents dimension 0 has 2 elements, expected 3
;; JVM, wasm P1, component: #(1 2 NIL)
(make-array '(2 3) :initial-contents (list (list 1 2) (list 4 5 6)))
;; interpreter: MAKE-ARRAY :initial-contents dimension 1 has 2 elements, expected 3
;; JVM:  ELT: The value 2 is not of type (INTEGER 0 (2))
;; wasm: The value 2 is not of type (INTEGER 0 (2))   (unnamed: the program never spells elt)
```

The compiled lowering (`LispMacroExpander.lowerInitialContentsMakeArray`) fills a rank-1 array up
to `(length contents)` -- a short list pads with `NIL`, a long one overruns into the `%aset` bound
check -- and reads each rank >= 2 row with `elt`, which since `elt` of a list outside it became a
type-error (`.kb/error-handling.md`, "elt of a LIST outside it") signals on a short row instead of
padding. CL requires the contents' shape to match the dimensions.

Goal: the compiled fill checks each level's length against its dimension and signals the
interpreter's text, identically on the JVM and both wasm backends. Mind the trap that sank the
first `elt` attempt: an eager `(error 'type-error ...)` in a lowering the compiler introduces is
invisible to the gates that decide whether a condition layout exists (`usedLayoutTags`); the
interpreter's report is a plain `error`, which may avoid that, but confirm `(print 42)` and a
`make-array` program with no handler still compile on every backend.

Pinned today by `JvmLispCompilerTest#compileAndRunMakeArrayInitialContentsWalksAListWithACursor`
and `WasmLispCompilerIntegrationTest#makeArrayInitialContentsWalksAListWithACursor` (`:ERROR` for the
two short-row cases).
