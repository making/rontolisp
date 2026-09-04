# `flet` / `labels` (local functions)

`LispMacroExpander` expansions (CL_MACROS; no per-backend codegen). `expandFletLabels`:

```lisp
(flet ((f (x) (+ x 1))) (f 2))          ; -> (let ((__flet0_f (lambda (x) (+ x 1)))) (funcall __flet0_f 2))
(labels ((f (n) ... (f ...))) (f 5))    ; -> (let ((__labels1_f nil))
                                        ;      (setq __labels1_f (lambda (n) ... (funcall __labels1_f ...)))
                                        ;      (funcall __labels1_f 5))
```

## Body rewrite (`rewriteLocalCalls`, Lisp-2)
- Only call position `(f args...)` -> `(funcall var args...)` and `(function f)`/`#'f` -> `var`. A bare `f` stays a variable reference.
- Shape-awareness mirrors `UserMacroExpander.expandAll`: quote/defmacro/defpackage verbatim; let/do binding names, lambda/defun parameter lists (defaults ARE rewritten), dolist/dotimes/with-open-file spec vars, case-family keys, defstruct slot names stay.
- Nested `flet`/`labels` shadow: an inner same-name def is removed from the map for the inner body; flet defs are walked with the outer map, labels defs with the shadowed one.
- `loop` clauses are walked generically, so a `for` destructuring pattern colliding with a local function name would misrewrite (same limitation as UserMacroExpander).
- **Ordering trap: the improper-list test runs BEFORE the non-symbol-head test.** The non-symbol-head branch rebuilds from `cons.toList()`, DROPPING an improper tail, so a loop pattern `((field . value) . rest)` came back as `((field . value))` and `rest` was unbound. An improper list is never a call form.

## Lowering and pruning
- **labels = letrec**: bind to nil, `setq` each to its lambda; `FreeVarAnalyzer.findCapturedVars` boxes the vars, so mutual recursion works on all four backends.
- **A local no surviving reference names is dropped, binding and all** — unconditional, every backend (constructing the closure is the binding's only effect). Reachability runs on the REWRITTEN forms: every real reference is an occurrence of the unique `__<op><n>_<name>` variable, so roots are the rewritten body forms plus, for `labels`, a kept definition's rewritten lambda; an `flet` definition contributes no edges. Over-finding (quoted data) only KEEPS a local. Needed because the labels lowering constructs every closure up front: when `ConstantCaseArmPruner` deletes the arm holding the only `#'state` reference (chipz zlib states, `.kb/library-defun-pruning.md`), the dead lambda still compiled in. It changes emitted bytes at EVERY level, so older "no-flag byte-identical" claims are historical.
- **Unique variable names** `__<op><counter>_<name>` (static `FLET_COUNTER`), not fixed names: a JVM let-init lambda capturing a same-named outer variable miscompiles. Counter values differ between the analyzers'/interpreter's throwaway expansions and the compile one, and depend on how much the macro-time evaluator evaluates (demand-driven since macro-time globals went lazy, `.kb/defmacro-backquote.md`). Renumbering in a diff is not a behavior change; output varying between JVM RUNS of one jar IS a bug (`.kb/emitted-output-determinism.md`). Hence no ci-spec macroexpand case.
- **Lambda lists are desugared in the expansion** via `LambdaLists.expand`: `LambdaLists.desugarProgram` does not look inside flet definition lists, `FreeVarAnalyzer.extractParamNames` chokes on raw `(b 10)` optionals, and it puts defaults into expression position for the body rewrite.
- **Def-name validation**: duplicate names and names in `PackageRegistry.specialOperatorNames()` are `IllegalArgumentException`. Shadowing an ordinary built-in function like `list` is allowed.

## Wiring
`expandBuiltinMacro` + evaluator + Jvm/WasmExprCompiler + `NoGcWasmCompiler.expandMacro` (`--no-gc` then fails on the lambda, as before) + `FreeVarAnalyzer` (both walks, expand-before-walking) + a `UserMacroExpander.expandAll` case (def names/lambda lists kept so a user macro of the same name is not expanded there).

## Caveats
- Interpreter-only: the rewrite precedes user-macro expansion at eval time, so a user macro called in an flet body receives already-rewritten argument forms; one that quotes an argument as *data* sees `(funcall __flet0_f ...)`. The compile path is unaffected (UserMacroExpander runs first).
- `macrolet`/`symbol-macrolet` unimplemented (`.todo/034-local-function-definition.md`).
- **wasm-GC fusion rides this lowering**: `WasmLetCompiler` registers a `__FLET*`-named binding whose init lambda has plain params and a single closed integer-tree body (the `(block name expr)` wrapper is unwrapped), and the fusion classifier substitutes it at `(funcall __FLETn_f ...)` sites — `.kb/wasm-int-fusion.md`. The lambda still compiles normally; `labels` bindings (nil-then-setq) never register.

## Tests
`LispMacroExpanderTest.labelsDropsALocalNoSurvivingReferenceNames`, `.labelsKeepsALocalReferencedOnlyAsAValue`, `.aSelfRecursiveLabelsLocalNothingElseNamesIsDropped`, `.fletDropsAnUnreferencedLocal`.
