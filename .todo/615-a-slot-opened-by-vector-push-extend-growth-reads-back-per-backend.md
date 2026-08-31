# A slot opened by `vector-push-extend` growth reads back differently per backend (and crashes on a character vector)

Difficulty: Medium

Found 2026-08-31 while doing `.todo/614` (which made the GROWN CAPACITY one
policy for all four backends -- doubling -- so the slots between the fill
pointer and the new dimension are now routinely reachable without an explicit
`extension` argument).

`aref` may read any slot below the DIMENSION, not just below the fill pointer.
The slots a growth opens answer:

```lisp
(let ((v (make-array 2 :fill-pointer 0 :adjustable t)))
  (dotimes (i 3) (vector-push-extend i v))
  (list (array-dimension v 0) (aref v 3)))
;; SBCL 2.2.9: (4 0)   interpreter: (4 NIL)   JVM: (4 NIL)   WASM: (4 NIL)

(let ((s (make-array 2 :element-type 'character :fill-pointer 0 :adjustable t)))
  (dotimes (i 3) (vector-push-extend #\a s))
  (list (array-dimension s 0) (aref s 3)))
;; SBCL 2.2.9: (4 #\Nul)   interpreter: (4 #\Nul)
;; JVM:  NullPointerException "Cannot load from int array because the return
;;       value of Prog._aref1(Object, Object) is null"
;; WASM: wasm trap: cast failure
```

So a character vector grown by `vector-push-extend` CRASHES on the compile
paths when a slot above the fill pointer is read, because both grow by
appending nulls (`JvmArrayRuntimeBuilder`'s `_vectorPushExtend` grow loop:
`list.add(null)`; `WasmArrayCompiler.compileVectorPushExtend`:
`array.new` with a null init) regardless of the REMEMBERED element type. The
interpreter already fills them with that type's own zero
(`LispArray.vectorPushExtend` -> `ArrayElementTypes.defaultElement`, and
`LispString`'s int buffer zero-fills to `#\Nul`), which is the rule `.todo/611`
established for `make-array`'s unsupplied elements -- growth is the same
question and the compile paths answer it differently.

`adjust-array` without `:initial-element` opens the same kind of slot; check it
in the same pass rather than fixing push-extend alone.

Not a `.todo/614` regression: an explicit `(vector-push-extend c s 10)` opened
ten such slots before too. Doubling only made the default path reach them.

The general (element type `t`) vector agrees across all four backends at `NIL`
and differs from SBCL's `0`; CLHS leaves an unwritten element's value
undefined, so leave that alone -- `NIL` is this project's answer for `t`
(`.kb/array-literals.md`, "The first trace the element type leaves is the
fill").

Fix by making the two compile paths open grown slots through the SAME default
as `make-array` (`ArrayElementTypes.defaultElement`, keyed by the element type
code the JVM header slot / the WASM meta marker already carries) rather than
with a raw null. That decides one open question on the way: this project's
character fill is `#\Space` (what `make-string` and a rank-n character array
give), while the interpreter's grown string slots and SBCL's are `#\Nul`, so
the interpreter has to move to whichever is chosen. Prefer `#\Space` -- one
fill rule for the whole surface beats matching SBCL on a value CLHS leaves
undefined -- but say so in `.kb/array-literals.md` where that rule is written.

Pin `(aref v <above the fill pointer>)` after a growth run on all four
backends: `LispEvaluatorTest` + `JvmLispCompilerTest` +
`WasmLispCompilerIntegrationTest` + a ci-spec case next to
`vector-push-extend-growth-cross-backend` (`.kb/adjustable-arrays.md`).
