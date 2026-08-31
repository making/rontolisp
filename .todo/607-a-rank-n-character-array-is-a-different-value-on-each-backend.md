# A rank-n character array is a different value on each backend, and the JVM refuses to build one

Difficulty: Medium

Found while landing `.todo/604` (the array type lattice), which had to route
AROUND it. `(make-array '(2 2) :element-type 'character :initial-element #\a)`,
one program, three answers:

```lisp
(list (stringp b) (array-element-type b) (array-dimensions b) (type-of b))
; interpreter   (NIL       T         (2 2) (SIMPLE-ARRAY T (2 2)))
; wasm (both)   (T         CHARACTER (2 2) STRING)
; JVM           error: make-array: :fill-pointer requires a rank-1 array
; SBCL 2.2.9    (NIL       CHARACTER (2 2) (SIMPLE-ARRAY CHARACTER (2 2)))
```

Three separate bugs in one shape:

1. **The JVM refuses the call**, with a message about a keyword the program
   never passed -- the rank-1 character path is being taken for a rank-2
   request, and its fill-pointer check fires.
2. **wasm answers `T` to `stringp`** for a RANK-2 array. A string is a rank-1
   character array; nothing of rank 2 is a string, and every string operation
   (`char`, `subseq`, `length`) then meets a value it cannot index.
3. **The interpreter loses the element type**: a rank-n character array falls
   into the general boxed representation, whose `array-element-type` is `t`.

The rank-1 case -- the one that actually occurs in library code -- agrees on all
four backends and answers `STRING`, so this is the untravelled corner of the
"a character vector is a marked general array OR a string after normalization,
two representations" note in `.kb/declarations-type-checks.md`. `.todo/604` made
`type-of`'s array arm fire only where the value's `%class-designator` is the
uninformative `T`, which keeps every rank-1 character array on the STRING answer
on all four backends; the rank-2 divergence above is what is left.

Decide the model FIRST and write it into `.kb/array-literals.md`: either a
character element type above rank 1 keeps the character marking on every backend
(then `stringp` must read the rank, which is `.todo/605`'s machinery, and
`array-element-type` must answer `CHARACTER` on the interpreter too), or it
degrades to the general representation on every backend (then wasm must stop
marking it and stop answering `stringp`). Do not fix one backend alone.

Behavior must be identical on all four backends: rows in `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` and a ci-spec case,
plus the `type-of` line in `.kb/declarations-type-checks.md`'s "known
divergence" paragraph, which this item deletes.
