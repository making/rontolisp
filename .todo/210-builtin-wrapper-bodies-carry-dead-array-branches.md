# The built-in wrapper bodies carry array branches that are dead in an array-free program

Found 2026-07-29 while closing `.todo/209`. The JVM gate check added there
(`JvmClassShaker.unresolvedSelfMethods`, run on every build) reports, for a
program as small as `(print (+ 1 2))`:

```
_aref1     called from +, -, *, $div, MIN, MAX, REVERSE, FIND, FIND-IF, FIND-IF-NOT,
           POSITION, POSITION-IF, POSITION-IF-NOT, COUNT, COUNT-IF, REMOVE-DUPLICATES,
           NCONC, EVERY, SOME, REMOVE, REMOVE-IF, REMOVE-IF-NOT, SUBSTITUTE, SORT,
           STABLE-SORT, COPY-SEQ, REPLACE, SUBSEQ, STRING=, STRING-EQUAL,
           STRING-TRIM, STRING-LEFT-TRIM, STRING-RIGHT-TRIM
_arrayMake called from REVERSE, REMOVE-DUPLICATES, REMOVE, ..., STRING-EQUAL
_aset1     called from REVERSE, REMOVE-DUPLICATES, REMOVE, ..., STRING-EQUAL
```

Every one of those is a `BuiltinFunctionWrappers` wrapper -- injected in EVERY
class whether or not the program mentions the operator -- and every one calls
into an array runtime the class does not contain. They do not crash: each call
sits behind a runtime type test (`%arrayp` / `vectorp`) for a value that cannot
exist without the runtime that builds it. But the compiler cannot prove that, so
`.todo/209` had to special-case them: a dangling call whose ONLY callers are
wrappers is skipped rather than treated as an under-predicted gate. Without the
exclusion every compiled class dragged in the whole array + hash runtimes,
about 35 KB.

## Goal

Delete the exclusion by deleting its cause: gate the array arm of each sequence /
numeric lowering on `Ctx.usesArrays`, exactly the way `JvmStringpCompiler` gates
the character-vector arm and `expandClassOf` (todo-209) gates the `hash-table-p`
clause. With no array runtime there is no array value, so the arm is provably
dead and emitting it is pure cost.

Two payoffs beyond tidiness:

- **Array-free classes shrink.** 30-odd wrapper bodies stop carrying an array
  dispatch, and the JVM lowerings behind them stop emitting one for user code
  too.
- **The gate check gets its full strength back.** Today a genuinely reachable
  dangling call inside a wrapper is invisible; `#'funcall`'s `(apply f r)` was
  exactly that shape, and it was found only because it needed the eval runtime
  UNCONDITIONALLY. The next one that needs a gated runtime unconditionally from
  a wrapper body will not be caught.

## Notes

- The lowerings are shared with the interpreter and WASM, so the flag has to be
  a parameter (like `expandClassOf(cons, hashTablesExist)`), not a global.
  Interpreter and both WASM backends always pass "arrays exist" -- their array
  primitives are unconditional.
- Do it lowering by lowering, checking the wrapper-caller list above shrinks
  each time; when it is empty, drop the `wrapperMethodNames.containsAll(...)`
  skip in `JvmLispCompiler` and the paragraph that explains it in
  `.kb/adjustable-arrays.md`.
- Whether the same treatment is worth it for `usesFloatArray` / `usesIntArray`
  (the `_fv*` / `_iv*` tiers) should be answered in the same pass.

Code: `JvmLispCompiler` (the `wrapperMethodNames` skip at the end of the compile
pass), `BuiltinFunctionWrappers.WRAPPER_DEFS`, the sequence lowerings in
`LispMacroExpander`, `JvmStringpCompiler` (the model to copy).
