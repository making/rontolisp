# vectorp answers T for an array of any rank, and so does (typep a 'vector)

Difficulty: Low

Found while landing `.todo/603`. A vector is a rank-1 array; every other rank is
not one. Against SBCL 2.2.9:

```lisp
(vectorp #2A((1 2) (3 4)))                  ; T    SBCL: NIL
(vectorp (make-array nil))                  ; T    SBCL: NIL
(typep #2A((1 2)) 'vector)                  ; T    SBCL: NIL
(typep #2A((1 2)) 'simple-vector)           ; T    SBCL: NIL
```

`Environment`'s `vectorp` built-in tests only the VALUE KIND (`LispString` /
`LispArray` / `LispFloatArray` / `LispIntVector`) and never the rank, and
`LispMacroExpander.makeTypeTest`'s atomic `VECTOR`/`SIMPLE-VECTOR` arm says so in
its own comment ("The rank is NOT checked"). The same rank-blindness is why
`(typep a '(simple-array single-float (2 2)))` answers nil -- that half is
`.todo/604`, which owns the compound specifier and the lattice; this item is only
the ATOMIC `vectorp` / `vector` / `simple-vector` answer.

`length` already refuses a rank != 1 array ("argument is not a sequence"), so the
rank is available everywhere the test needs it; a packed integer vector and a
string are rank-1 by construction and stay `T`.

Behavior must be identical on all four backends (`.kb/declarations-type-checks.md`
owns the lattice and names the pinning tests): add rows in `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` and a ci-spec case.
Check `subtypep`'s vector rows and every internal `vectorp` caller before
narrowing -- some sequence lowering may be leaning on the loose answer.
