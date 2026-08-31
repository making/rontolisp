# A general array does not remember its declared element type

Difficulty: High

Found while landing `.todo/607` (a rank-n character array). Every specialized
element type except the packed floats degrades to the general representation
above rank 1, and the general representation carries NO element type -- so the
declared type is simply lost:

```lisp
(let ((a (make-array '(2 2) :element-type '(unsigned-byte 8))))
  (list (array-element-type a) (type-of a) (aref a 0 0)))
; here          (T   (SIMPLE-ARRAY T (2 2))               NIL)
; SBCL 2.2.9    ((UNSIGNED-BYTE 8) (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (2 2)) 0)
```

The same three answers are wrong for `character` at rank 2 (`T` instead of
`CHARACTER`), for `(unsigned-byte 8|16|32)` at rank 2+, and for any packed
element type combined with `:fill-pointer`/`:adjustable` at rank 1 -- every
shape where `make-array` chooses the boxed representation after being told a
narrower element type. `.todo/607` deliberately did NOT special-case the
character half, because one field fixes all of them and half a fix is a second
inconsistency.

Three things to build, and they are one change:

1. **A remembered element type on the general array, on all four backends.**
   The JVM's header is `Object[]{dims, fillPointer, adjustable, ...}` with the
   marker encoded as the header LENGTH (4 = mutable character vector, 5 =
   displaced, 6 = packed) -- adding a slot means re-reading every length test
   (`.kb/array-literals.md`, `JvmArrayRuntimeBuilder`'s class comment). wasm's
   is the `(dims . (meta . data))` cons chain whose meta offset holds 0 or 1
   (`_charvec_p` owns that invariant, `WasmStringRuntimeBuilder`).
   `LispArray` has no field at all. Measure the size cost on the `hello-clack`
   Worker row before committing to a shape -- a slot every array carries is
   paid by programs that never ask.
2. **`array-element-type` answers it**, and `type-of` builds
   `(SIMPLE-ARRAY <et> dims)` from it (`.kb/declarations-type-checks.md`, "The
   array type lattice"). `makeArrayTypeTest`'s `upgradedArrayElementType` must
   then stop folding a rank-n `(unsigned-byte 8)` request to `t`, or `typep`
   and `type-of` disagree again.
3. **The unsupplied element takes the element type's own zero**, not `nil`.
   `.todo/607` gave the character family `#\Space` and the float families `0.0`
   (on all four -- the float default had been on the compile backends only), so
   what is left is `0` for the packed integer widths, which have no fallback
   default anywhere. That default belongs with the remembered element type: it
   is the same question read at allocation instead of at `array-element-type`.

Behavior must be identical on all four backends: rows in `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` and a ci-spec case
whose expected text is SBCL 2.2.9's on that very program, plus the model in
`.kb/array-literals.md` ("A SPECIALIZED element type above rank 1 is the
general array") and the `array-element-type` paragraph in
`.kb/declarations-type-checks.md`.

If the measurement says the slot is not worth its bytes, that is the result:
write the numbers into `.kb/array-literals.md` and close the item on them.
