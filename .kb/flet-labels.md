# `flet` / `labels` (local functions)

`LispMacroExpander.expandFletLabels` (CL_MACROS; no per-backend codegen). `flet` -> `let` of
lambdas; `labels` = letrec -- bind to nil, then `setq` each lambda, so
`FreeVarAnalyzer.findCapturedVars` boxes the vars and mutual recursion works on all four backends.

- `rewriteLocalCalls` (Lisp-2): only call position `(f ...)` -> `(funcall var ...)` and `#'f` ->
  `var`; a bare `f` stays a variable. Shape-awareness mirrors `UserMacroExpander.expandAll`.
  Nested definitions shadow: flet defs walked with the outer map, labels defs with the shadowed one.
- `loop` clauses are walked, but a list after `for`/`as`/`with`/`and`/`using`/`of-type`/`into`
  is data (`LispMacroExpander.isLoopDataKeyword`, shared with `UserMacroExpander`'s walk), so a
  destructuring pattern whose head names a local function or a macro stays a pattern (since
  2026-10-31; parenscript's `for (var p) = ...` beside its `(defmacro var ...)` compiled
  `var` as `defparameter`). ci-spec `loop-parallel-and-destructuring`.
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
- **A `(setf place)` local** (`localFunctionKey`) is keyed `%setf-PLACE`, its block named `PLACE`.
  `rewriteSetfOfLocal` turns a `(setf (place args) v)` naming it into `setfFunctionCall` with the
  writer's `#'`, so the order is the global one's, and a modify macro on such a place walks through
  its expansion. A place whose head is only a local READER keeps its head: it writes through the
  global `(setf place)`, as SBCL's does (before 2026-11-05 it became a `funcall` place). Consumers:
  eclector's `set-standard-syntax-types`, pngload's `decode.lisp`. Pin: ci-spec
  `flet-and-labels-local-functions`.
- **A call only a macro's expansion spells is rewritten too, on every backend.** The compile
  path expands user macros first. The interpreter's `flet`/`labels` arm does the same walk
  (`preExpandLocalMacros`) when the tree heads a form with a user macro or `macrolet`, and
  memoizes the expansion per site in `userMacroExpansions` (`expandedPerSite`, as `symbol-macrolet`),
  so the walk costs once and not per entry, which is why a recursive `flet` keeps the stack it had
  (`treeHasHead` stays iterative: cl-mustache). Until 2026-11-05 only a tree naming a `macrolet` was
  walked, and `(flet ((f ...)) (call-it f))` was `F is undefined` on the interpreter alone
  (fast-http's `skip-while`, eclector's `token-state-machine`). A macro in a dead branch of the
  body now expands at entry, as on the compile path; one defined by the body itself still expands
  when reached. Pin: ci-spec `flet-and-labels-local-functions`, `LispEvaluatorTest#evalFletBasic`.
- **A local function shadows a macro of its name in the walk too** (`LispEvaluator.shadowedMacros`,
  `UserMacroExpander.isMacroInWalk`; a compiler macro likewise): the body, and for `labels` the
  definitions, keep the call for the rewrite, an inner `macrolet` of the name shadows back, and a macro
  body runs without the set, in the null lexical environment. Without it `expandAll` expanded the
  macro: `(defmacro m ...) (flet ((m ...)) (m 1))` called the macro on JVM and both WASM until
  2026-11-05, and the interpreter's new walk would have joined them.
- `macrolet`/`symbol-macrolet` unimplemented (`.todo/034-local-function-definition.md`).
- **wasm-GC fusion rides this lowering**: `WasmLetCompiler` registers a `__FLET*` binding whose
  init lambda has plain params and a closed integer-tree body, substituted at
  `(funcall __FLETn_f ...)` sites (`.kb/wasm-int-fusion.md`). `labels` bindings never register.

## Tests
`LispMacroExpanderTest.labelsDropsALocalNoSurvivingReferenceNames`,
`.labelsKeepsALocalReferencedOnlyAsAValue`, `.aSelfRecursiveLabelsLocalNothingElseNamesIsDropped`,
`.fletDropsAnUnreferencedLocal`.
