# `flet` / `labels` (local functions)

`LispMacroExpander.expandFletLabels` (CL_MACROS; no per-backend codegen). `flet` -> `let` of
lambdas; `labels` = letrec -- bind to nil, then `setq` each lambda, so
`FreeVarAnalyzer.findCapturedVars` boxes the vars and mutual recursion works on all four backends.

- `rewriteLocalCalls` (Lisp-2): only call position `(f ...)` -> `(funcall var ...)` and `#'f` ->
  `var`; a bare `f` stays a variable. Shape-awareness mirrors `UserMacroExpander.expandAll`.
  Nested definitions shadow: flet defs walked with the outer map, labels defs with the shadowed one.
- `loop` clauses are walked generically, so a `for` destructuring pattern colliding with a local
  function name misrewrites (same limitation as UserMacroExpander).
- **Ordering trap: the improper-list test runs BEFORE the non-symbol-head test.** That branch
  rebuilds from `cons.toList()` and DROPS an improper tail. An improper list is never a call form.
- **A local no surviving reference names is dropped, binding and all** -- unconditional, every
  backend, reachability computed on the REWRITTEN forms. Needed because the labels lowering
  constructs every closure up front (`ConstantCaseArmPruner`, `.kb/library-defun-pruning.md`).
  Changes emitted bytes at every level.
- **Unique names** `__<op><counter>_<name>` (static `FLET_COUNTER`): fixed names miscompile a JVM
  let-init lambda capturing a same-named outer variable. Counter values differ between throwaway
  and compile expansions, so renumbering in a diff is not a behavior change; variation between JVM
  RUNS of one jar IS (`.kb/emitted-output-determinism.md`). Hence no ci-spec macroexpand case.
- Lambda lists desugared in the expansion via `LambdaLists.expand`. Duplicate def names and
  `PackageRegistry.specialOperatorNames()` are `IllegalArgumentException`; shadowing `list` is not.
- Wiring: `expandBuiltinMacro` + evaluator + Jvm/WasmExprCompiler + `NoGcWasmCompiler.expandMacro`
  + `FreeVarAnalyzer` (both walks) + a `UserMacroExpander.expandAll` case.
- Interpreter-only: the rewrite precedes user-macro expansion, so a user macro in an flet body
  receives already-rewritten argument forms. Compile path unaffected.
- `macrolet`/`symbol-macrolet` unimplemented (`.todo/034-local-function-definition.md`).
- **wasm-GC fusion rides this lowering**: `WasmLetCompiler` registers a `__FLET*` binding whose
  init lambda has plain params and a closed integer-tree body, substituted at
  `(funcall __FLETn_f ...)` sites (`.kb/wasm-int-fusion.md`). `labels` bindings never register.

## Tests
`LispMacroExpanderTest.labelsDropsALocalNoSurvivingReferenceNames`,
`.labelsKeepsALocalReferencedOnlyAsAValue`, `.aSelfRecursiveLabelsLocalNothingElseNamesIsDropped`,
`.fletDropsAnUnreferencedLocal`.
