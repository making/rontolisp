# A :element-type held in a VARIABLE picks a different representation on each backend

Difficulty: Medium

Found while landing `.todo/611`. When `make-array`'s `:element-type` is not a
literal at the call site, no expansion-time recognizer can see it, so the
compile paths lower the call through
`LispMacroExpander.lowerRuntimeElementTypeMakeArray` -- which branches on
CHARACTER alone and sends everything else to the plain general array. The
interpreter has the value in hand and picks the real representation. The two
answer differently, and not only about the element type:

```lisp
(defvar *et8* '(unsigned-byte 8))
(defvar *etc* 'character)
(print (array-element-type (make-array 4 :element-type *et8*)))
(print (array-element-type (make-array '(2 2) :element-type *etc*)))
(print (aref (make-array 4 :element-type *et8*) 0))
; interpreter          (UNSIGNED-BYTE 8) / CHARACTER / 0
; JVM + both wasm      T                 / T         / NIL
; SBCL 2.2.9           (UNSIGNED-BYTE 8) / CHARACTER / 0
```

The `aref` row is the one that bites: a program that computes its element type
gets a packed vector of zeros here and a boxed vector of `nil` there, so
`(incf (aref buf i))` works on one backend and signals on the other. This
predates todo-611 -- the representation choice always differed -- and todo-611
only added the CHARACTER row to it (the interpreter now remembers the element
type; the lowering's general branch still does not stamp one).

The fix is one shape: `lowerRuntimeElementTypeMakeArray` must cover the whole
UPGRADE space, not just the character half. `am.ik.rontolisp.ArrayElementTypes`
is that space (7 codes) and its `codeOf` is the recognizer; the lowering should
expand to a `cond` over the runtime designator with ONE literal `make-array`
per code, so each branch reaches the same recognizers a literal spelling does.
That is 7 branches of expansion at every such call site, which is why it is
worth measuring before committing to it -- a program with the call inside a
loop body pays for all seven. If the bloat does not pay, the alternative is a
runtime helper per backend that takes the designator as a value, which is a
bigger change but expands once.

Both halves need the same rank rule the literal path has (rank 1 packs, rank n
degrades and remembers), and the unsupplied element must take
`ArrayElementTypes.defaultElement`'s zero on every branch.

Behavior must be identical on all four backends: rows in `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` and a ci-spec case
whose expected text is SBCL 2.2.9's on that very program, plus the paragraph in
`.kb/array-literals.md` ("The degraded array REMEMBERS its element type", "What
still answers `t`, on purpose") that currently records the gap.
