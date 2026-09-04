# The `map*` family over N lists

**Invariant**: `mapcar`/`mapc`/`mapcan`/`maplist`/`mapcon`/`mapl` take a function plus ANY number
of lists, on all four backends, in call position AND as a value; one arg per list; stop at the
SHORTEST list; zero lists is an error, never a one-list call.

**Trap**: the count is static in call position, so a backend compiling only `(op f l)` emits a
plausible WRONG list, not an error.

## Implementations

- `LispMacroExpander.expandMapFamily` (`maplist`/`mapcon`/`mapl`; `LispEvaluator.evalCons` and
  both `compileCons`es). Axes: `tails` (cdrs, not cars), `MapAccumulation`
  `COLLECT`/`CONCATENATE`/`DISCARD` (`DISCARD` answers the FIRST list). Lowering: `let`
  (function, then lists, left to right), one `listp` guard per list, `do` ending on
  `(or (atom #c0) (atom #c1) ...)` -- one list gives the bare `(atom #c0)`. `do` steps before
  testing, so `cdr` never sees the terminating atom.
- Inline emitters `Jvm/WasmMapcarCompiler`, `Jvm/WasmMapcCompiler`, `Jvm/WasmMapcanCompiler`:
  slot per list, exit when ANY cursor is not a cons, one `car` per list,
  `_invoke_<nLists>`/`dispatch_<nLists>`. **`ctx.indirectCallArities` must get `nLists`, not 1**
  -- a stale `1` trapped a two-list `mapc` on WASM. A literal `#'name`/`'name` of matching arity
  is called DIRECTLY via `Wasm/JvmDesignatorCall`, registering no arity
  (`.kb/optimize-dead-code-elimination.md`). `mapc`'s first list gets its own slot (it is the
  return value); `mapcan` walks the list slots.
- Interpreter: `LispEvaluator.mapFamilyValues` + `mapValues`/`mapForEffect`/`mapcanValues` --
  **the reference the compile backends are diffed against; widen it first.**
- `BuiltinFunctionWrappers.mapFamilyWrapper` (value path, RUNTIME count): one
  `(lambda (f l &rest more) ...)` for all six -- `(op f l)` when `more` is nil, else a `do` over
  `(cons l more)` ending on `(member nil ls)`, stepped by a single-list `mapcar` of `cdr`.
  **`(member nil ls)` detects PROPER-list exhaustion only**; improper lists are caught by the
  call-position `atom` test, not here.
- Wrappers are injected UNGATED (not in `REFERENCE_GATED_FUNCTIONS`); `--optimize` strips
  unreferenced ones. **Never gate them on `referencesFunctionValue`**: a reference the gate
  misses then answers one list silently.
- Interpreter built-ins ARE the function objects and use no wrappers, so `maplist`/`mapcon`/`mapl`
  also need `defineFunction` registrations; without them `#'maplist` is "The function MAPLIST is
  undefined" while both compile backends wrap it happily.

## Errors

- Every list position is guarded: a non-list signals `<NAME>: argument is not a list ... (use map
  for strings/vectors)` on interpreter and JVM, traps `unreachable` on WASM; `nil` is a valid
  empty list.
- `(mapcan #'list)` -> `<NAME> expects at least 2 arguments`: `LispEvaluator.requireMapLists`,
  `expandMapFamily`, and an `UnsupportedOperationException` from the emitter for
  `mapcar`/`mapc`/`mapcan` (a compile error; the count is static).

## `map` is a different lowering

- `expandMap` reads operands by INDEX (it serves vectors and strings); its list read carries a
  cons cursor with the indexed read as fallback (`.kb/seq-coerce-runtime.md`). This family never
  had that defect.
- `(map 'string ...)` collects pieces and joins by repeated PAIRWISE concatenation
  (`joinStringPiecesReversed`), not per-element `%string-concat`. **Every literal
  `(coerce x 'string)` runs this body**: `coerceToStringBody` is a `(map 'string #'identity ...)`
  form under `%seq-to-string`.
- Open defect, untouched: `make-string` yields a mutable character VECTOR on the compiled backends
  whose `(char v i)` renders the whole vector per access -- quadratic in the READ
  (`.kb/adjustable-arrays.md`, `.kb/geom.md`).

## Divergences from CL

- `mapcan`/`mapcon` use non-destructive `append`, not `nconc` -- documented
  (`doc/*/reference/functions/mapcan.md`); callers relying on splicing see fresh conses.
- `every`/`some` are a separate family (SEQUENCES, coerced to lists first, no `listp` guard),
  N-ary via `LispMacroExpander.expandEverySomeFamily` + `BuiltinFunctionWrappers.everySomeWrapper`;
  no lowering shared.

## The wrapper's `apply` and the WASM emission gate

`mapFamilyWrapper` bodies call `apply`; WASM's `_apply` is gated on `usesEval`, which scans the
SOURCE, and wrappers are injected AFTER that scan. **Trap**: `#'mapcar` as a value in a program
with no other `apply` got a nil-answering `_apply` stub --
`(funcall #'mapcar #'list '(1 2) '(3 4))` answered `(NIL NIL)`.
`BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` lists such wrappers (the `map*` six,
`every`/`some`, `funcall`); `referencesApplyingWrapper` answers "reachable as a value here" and
`WasmLispCompiler.usesEval` consults it. **Any new wrapper whose body calls `apply` must join that
set**; any runtime gated on a program scan has the same trap.

## Tests

- ci-spec, all four backends: `mapcar-as-a-first-class-value-over-many-lists`,
  `{mapc,mapcan,maplist,mapcon,mapl}-over-many-lists`.
- `LispEvaluatorTest#mapFamilyOverMultipleLists, #mapFamilyAsValuesOverMultipleLists,
  #mapFamilyRejectsACallWithNoList, #mapFamilySignalsErrorOnNonList,
  #mapcarAsValueOverMultipleLists`.
- `JvmLispCompilerTest#compileAndRunMapFamilyMultipleLists,
  #compileAndRunMapFamilyAsValuesOverMultipleLists, #compileAndRunMapcarAsValueOverMultipleLists`.
- `WasmLispCompilerIntegrationTest#mapFamilyMultipleListsCompilesAndRuns,
  #mapFamilyAsValuesOverMultipleListsCompilesAndRuns,
  #mapcarAsValueOverMultipleListsCompilesAndRuns, #mapFamilyTrapsOnNonList,
  #applyUsingWrapperReachedByFuncallCompilesAndRuns` -- each assertion of the last must be the
  ONLY form in its program.
