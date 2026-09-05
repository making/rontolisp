# 707. `coerce` and `concatenate` drop a packed FLOAT element type, while keeping a packed integer one

Difficulty: Medium

Found 2026-09-05 working `.todo/487` step 2, which asked for
`(coerce v '(array bfloat16))`. It does not work -- and neither does the same spelling
at either width that has existed for months, so this is not a bfloat16 gap:

```lisp
(array-element-type (coerce '(1 2) '(vector (unsigned-byte 8))))     ; => (UNSIGNED-BYTE 8)
(array-element-type (coerce '(1.0 2.0) '(vector single-float)))      ; => T
(array-element-type (coerce '(1.0 2.0) '(simple-array single-float (*)))) ; => T
(coerce #f(1.0 -2.0 0.5) '(array bfloat16))                          ; => #f(1.0 -2.0 0.5)
(coerce #bf16(1.0) '(array single-float))                            ; => #bf16(1.0)
(array-element-type (concatenate '(vector (unsigned-byte 8)) '(1 2) '(3))) ; => (UNSIGNED-BYTE 8)
(array-element-type (concatenate '(vector single-float) '(1.0 2.0) '(3.0))) ; => T
```

SBCL answers a specialized vector for every one of those. We answer a GENERAL vector
when the source is a list, and **the argument unchanged** when it is already a packed
array of another width -- a silent wrong answer in both directions, at every float
width, through both operators.

**The half that works shows the shape of the fix, and its own comment overstates what
was fixed.** `compiler/ConcatenateForms` normalizes a result-type designator to a
`ResultSpec(family, intWidth)`, and `packedVectorCoerce` lowers a nonzero `intWidth` to
the shared `%seq-int-vector` helper. Its class comment says this "retires the divergence
this file used to record as a re-evaluation trigger (`coerce` still DROPS the element
type)". It retires it for `(unsigned-byte 8|16|32)` and for nothing else. The float
widths were never added, and no test asks for one.

## Do

1. **Carry the element-type CODE, not a second width field.** `ResultSpec` should hold an
   `ArrayElementTypes` code rather than `intWidth`, so the packed families come from the
   closed code space instead of a hand-rolled list. `.todo/487` fixed four transcriptions
   of that same list on 2026-09-05 by deriving them from
   `ArrayElementTypes.specializedCodes()`; adding an `intWidth`-shaped `floatWidth` beside
   it would be the next transcription. Check `ResultSpec.intWidth`'s other readers before
   changing its shape.
2. One lowering per packed family, the way `%seq-int-vector` already is, so `coerce` and
   `concatenate` share it and every backend gets it at once.
3. **`bfloat16` is interpreter + JVM only** -- the other backends refuse the width by name
   (`.kb/bfloat16.md`), and the refusal has to reach this path where the representation is
   chosen, not only at a literal `make-array` (the mistake `.todo/487` found in
   `WasmArrayCompiler`: a guard on one spelling of an operation is not a guard).
4. Pin it keyed to `ArrayElementTypes.specializedCodes()` and not to a list of widths, on
   every engine -- the form `.todo/487` used, including verifying the pin fails when a
   width is removed from the generator.

## Not in scope

`.todo/487` step 2 also asked for a VECTORIZED bulk converter (11.9 Gelem/s widening,
`IntVector.lanewise(LSHL, 16)`). That was for the checkpoint load path, which no longer
needs it: a BF16 tensor now loads into a `#bf16` array as one `read-sequence` with no
conversion, and an F32 one is narrowed as it streams (`.todo/487` steps 3 and 4). Build
the correctness above first; add lanes only when a caller is measured to want them.
