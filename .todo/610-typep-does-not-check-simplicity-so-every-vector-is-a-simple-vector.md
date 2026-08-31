# typep does not check simplicity, so every vector is a simple-vector

Difficulty: Medium

The `typep` half of the pair `.todo/609` closed on the `subtypep` side. The
lattice now says `simple-vector` is strictly below `vector` (and `simple-array`
below `array`, `simple-string` below `string`), but the PREDICATE still treats
the `simple-` spellings as their general counterpart -- `makeArrayTypeTest`
maps `VECTOR`/`SIMPLE-VECTOR`/`ARRAY`/`SIMPLE-ARRAY` to one array test and the
four string spellings to `stringp`. Measured 2026-08-31, all four backends
agreeing with each other and disagreeing with SBCL 2.2.9:

```lisp
(typep (make-array 4 :fill-pointer 0) 'simple-vector)   ; here T   SBCL NIL
(typep (make-array 4 :fill-pointer 0) 'simple-array)    ; here T   SBCL NIL
(typep (make-array 4 :element-type 'character :fill-pointer 0)
       'simple-string)                                  ; here T   SBCL NIL
```

The simple/non-simple distinction is not new information the runtime has to
learn: `type-of` already spells it (`.todo/604` -- `(SIMPLE-VECTOR 4)` vs
`(VECTOR T 4)`), so the test exists; it is `array-has-fill-pointer-p` /
`adjustable-array-p` / `array-displacement` all answering false, and a packed
array is simple by construction. What has to be decided is where that costs
bytes: the `simple-` arm of `makeArrayTypeTest` gains a runtime check, and a
COMPOUND `(simple-array et dims)` specifier is the shape `type-of` hands back to
`typep`, so the check lands on a hot idiom. Measure it the way `.todo/605`
measured the rank check (the `zlib` size-report artifact at `--optimize=size`,
plus the array-free JVM program that must stay byte-identical) before choosing
between "always check" and "check only where the specifier says `simple-`".

Watch the interaction with the string side: a displaced string VIEW and a
mutable character vector are non-simple STRINGS on the compile backends
(`.kb/adjustable-arrays.md`), so `stringp` alone cannot answer `simple-string`,
while an interpreter `LispString` carries its own fill-pointer/displacement
fields. `.todo/607` is moving the rank-n character-array representation, so read
its `.kb/array-literals.md` decision first.

Behavior must be identical on all four backends
(`.kb/declarations-type-checks.md` owns the lattice, "The `simple-` names are
lattice EDGES"): rows in `LispEvaluatorTest` + `JvmLispCompilerTest` +
`WasmLispCompilerIntegrationTest` and a ci-spec case, with SBCL 2.2.9's answers
on the same program.
