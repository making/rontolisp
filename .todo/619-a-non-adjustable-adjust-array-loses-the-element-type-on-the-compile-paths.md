# A NON-adjustable `adjust-array` loses the element type on the compile paths

Difficulty: Medium

Found 2026-08-31 while doing `.todo/615` (which made the slots an adjustment
OPENS take the element type's zero on all four backends, but did not touch the
element type the RESULT carries).

`adjust-array` of an `:adjustable` array is fine everywhere: the array's own
identity survives, so `%array-become` leaves its remembered element type (and,
on the JVM, its character-vector header marker) exactly where it was.

The NON-adjustable case returns a FRESH array, and the compile path builds that
array from `LispMacroExpander.expandAdjustArray`'s `make-array`, which carries
`:initial-element`, `:fill-pointer` and `:adjustable` but NOT `:element-type`.
So the copy is a plain general array:

```lisp
(let* ((s (make-array 3 :element-type 'character :initial-element #\x))
       (r (adjust-array s 5)))
  (list (stringp r) (array-element-type r) (char-code (aref r 4))))
;; SBCL 2.2.9:  (T CHARACTER 0)
;; interpreter: (T CHARACTER 32)   -- the #\Space of .todo/615, otherwise right
;; JVM / WASM:  r is a general vector: stringp is NIL, array-element-type is T,
;;              and (char-code (aref r 4)) fails on a nil element
```

CLHS says the element type is not changed by `adjust-array`, and SBCL keeps it
on both halves, so this is a real conformance gap and not a lite-semantics
choice. The interpreter has kept it since `.todo/615` (its `adjustArray` carries
`elementTypeCode()` into the resized copy); only the two compile paths differ.

The obvious fix is one line -- have `expandAdjustArray` spell
`:element-type (array-element-type __adj_a)` -- because `.todo/612`'s
`lowerRuntimeElementTypeMakeArray` already turns a RUNTIME designator back into
the seven literal arms every backend's recognizer reads. **The cost is why this
is its own item**: measured 2026-08-31, `--optimize=size`, raw wasm, one call
site, arms inline (no prelude helper selected), the keyword alone is
**10,795 -> 15,059 bytes, +4,264 (+39%)**. The helper form
(`%make-array-et-fp`, `.kb/array-literals.md`) amortizes that across sites but
costs a fixed ~2.9 KB, and `LispPreludeLibrary.referencedBySurfaceForm` /
`LibraryDefunPruner` would both need `adjust-array` added to the surface fact
they key on (today they look for a `make-array` with a runtime `:element-type`,
which an `adjust-array` source does not contain until the expander runs).

`LispMacroExpander.makeArrayElementTypeCodes` has to move with it:
`Ctx.typedArrayCodes` is scanned from the SOURCE's `make-array` calls, so a
program whose only typed array arrives through an `adjust-array` expansion would
otherwise get a lowering whose arms the per-width gates never predicted. An
`adjust-array` call has to count as every specialized code, exactly as a runtime
designator already does -- which prices every `adjust-array` program into the
`array-element-type` width arms too.

One neighbour to fix in the same pass, same family and also pre-existing: on the
compile paths `(adjust-array "abc" 5)` -- an IMMUTABLE literal string, i.e. a
runtime `String` / `TYPE_STRING` rather than a cell -- dies in `%array-become`
("class java.lang.String cannot be cast to class java.util.ArrayList" / wasm
`cast failure`) where the interpreter answers a 5-long string. Verified against
`develop` at `.todo/615`'s merge base, so it is not that item's doing.

So: measure first, on real programs (`jzon` and `cl-ppcre` both adjust string
accumulators; `cffi`'s `enum.lisp` adjusts a general one), and decide between
the inline arms, the helper, and a narrower fix that only preserves the
CHARACTER case (the one that changes `stringp`, i.e. the one a program can
actually trip over). If the measurement says the general fix is not worth its
bytes, land the measurement and the narrow fix.

Pin whichever lands on all four backends: `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` + a ci-spec case next
to `opened-slot-fill-cross-backend`, and update
`.kb/adjustable-arrays.md`'s "Still open" note at the end of the
"A slot the growth OPENS holds the element type's zero" section.
