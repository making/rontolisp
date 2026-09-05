# The `map*` family over N lists

**Invariant**: `mapcar`/`mapc`/`mapcan`/`maplist`/`mapcon`/`mapl` take a function plus ANY number
of lists, on all four backends, in call position AND as a value; one arg per list; stop at the
SHORTEST list; zero lists is an error, never a one-list call.

**Trap**: the count is static in call position, so a backend compiling only `(op f l)` emits a
plausible WRONG list, not an error.

## Implementations
- `LispMacroExpander.expandMapFamily` (`maplist`/`mapcon`/`mapl`). Axes: `tails` (cdrs, not cars),
  `MapAccumulation` `COLLECT`/`CONCATENATE`/`DISCARD` (`DISCARD` answers the FIRST list). Lowering:
  `let` (function, then lists, left to right), one `listp` guard per list, `do` ending on
  `(or (atom #c0) (atom #c1) ...)`; `do` steps before testing, so `cdr` never sees the atom.
- Inline emitters `Jvm/WasmMapcarCompiler`, `Jvm/WasmMapcCompiler`, `Jvm/WasmMapcanCompiler`: slot
  per list, exit when ANY cursor is not a cons, `_invoke_<nLists>`/`dispatch_<nLists>`.
  **`ctx.indirectCallArities` must get `nLists`, not 1** -- a stale `1` trapped a two-list `mapc`
  on WASM. A literal `#'name`/`'name` of matching arity is called DIRECTLY via
  `Wasm/JvmDesignatorCall`, registering no arity (`.kb/optimize-dead-code-elimination.md`).
- Interpreter `LispEvaluator.mapFamilyValues` + `mapValues`/`mapForEffect`/`mapcanValues` --
  **the reference the compile backends are diffed against; widen it first.**
- `BuiltinFunctionWrappers.mapFamilyWrapper` (value path, RUNTIME count): one
  `(lambda (f l &rest more) ...)` for all six. **`(member nil ls)` detects PROPER-list exhaustion
  only**; improper lists are caught by the call-position `atom` test.
- Wrappers are injected UNGATED; `--optimize` strips unreferenced ones. **Never gate them on
  `referencesFunctionValue`**: a missed reference then answers one list silently. Interpreter
  built-ins ARE the function objects, so `maplist`/`mapcon`/`mapl` also need `defineFunction`
  registrations, else `#'maplist` is undefined while both compile backends wrap it happily.

## Errors
A non-list signals `<NAME>: argument is not a list ... (use map for strings/vectors)` on
interpreter and JVM, traps `unreachable` on WASM; `nil` is a valid empty list. `(mapcan #'list)`
-> `<NAME> expects at least 2 arguments` (`LispEvaluator.requireMapLists`, `expandMapFamily`, and
an `UnsupportedOperationException` from the emitter -- a compile error, the count is static).

## `map` is a different lowering
`expandMap` reads operands by INDEX (it serves vectors and strings), its list read carrying a cons
cursor with the indexed read as fallback (`.kb/seq-coerce-runtime.md`). `(map 'string ...)` joins
by repeated PAIRWISE concatenation (`joinStringPiecesReversed`); **every literal
`(coerce x 'string)` runs `coerceToStringBody`**. Open defect: `make-string` yields a mutable
character VECTOR whose `(char v i)` renders the whole vector per access -- quadratic in the READ
(`.kb/adjustable-arrays.md`, `.kb/geom.md`).

## Divergences from CL
`mapcan`/`mapcon` use non-destructive `append`, not `nconc` (documented). `every`/`some` are a
separate SEQUENCES family, N-ary via `LispMacroExpander.expandEverySomeFamily` +
`BuiltinFunctionWrappers.everySomeWrapper`; no lowering shared.

## The wrapper's `apply` and the WASM emission gate
`mapFamilyWrapper` bodies call `apply`; WASM's `_apply` is gated on `usesEval`, which scans the
SOURCE, and wrappers are injected AFTER that scan -- `#'mapcar` as a value in a program with no
other `apply` got a nil-answering stub. `BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` lists such
wrappers and `referencesApplyingWrapper` answers for them; `WasmLispCompiler.usesEval` consults
it. **Any new wrapper whose body calls `apply` must join that set.**

## Tests
ci-spec `mapcar-as-a-first-class-value-over-many-lists`,
`{mapc,mapcan,maplist,mapcon,mapl}-over-many-lists`; `LispEvaluatorTest`,
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` `#mapFamily*`/`#mapcarAsValue*` cases,
plus `WasmLispCompilerIntegrationTest#applyUsingWrapperReachedByFuncallCompilesAndRuns` -- each
assertion of the last must be the ONLY form in its program.
