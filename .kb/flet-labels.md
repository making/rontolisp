# `flet` / `labels` (local functions)

Both are `LispMacroExpander` expansions (CL_MACROS; no per-backend codegen).
`expandFletLabels` rewrites

```lisp
(flet ((f (x) (+ x 1))) (f 2))          ; -> (let ((__flet0_f (lambda (x) (+ x 1)))) (funcall __flet0_f 2))
(labels ((f (n) ... (f ...))) (f 5))    ; -> (let ((__labels1_f nil))
                                        ;      (setq __labels1_f (lambda (n) ... (funcall __labels1_f ...)))
                                        ;      (funcall __labels1_f 5))
```

- **Lisp-2 body rewrite** (`rewriteLocalCalls`): only call position `(f args...)`
  -> `(funcall var args...)` and `(function f)`/`#'f` -> `var`; a bare `f` stays a
  variable reference. The walker mirrors `UserMacroExpander.expandAll`'s
  shape-awareness (quote/defmacro/defpackage kept verbatim; let/do binding names,
  lambda/defun parameter lists (defaults ARE rewritten), dolist/dotimes/
  with-open-file spec vars, case-family keys, defstruct slot names stay) plus
  nested `flet`/`labels` scoping: inner same-name defs shadow (removed from the
  map for the inner body; flet defs are walked with the outer map, labels defs
  with the shadowed one). `loop` clauses are walked generically, so `for`
  destructuring patterns colliding with a local function name would misrewrite
  (same pre-existing limitation as UserMacroExpander).
- **labels = letrec lowering**: bind vars to nil, `setq` each to its lambda; the
  lambdas capture the vars, `FreeVarAnalyzer.findCapturedVars` boxes them, so
  mutual recursion works in all backends (verified on all four).
- **Unique variable names** (`__<op><counter>_<name>`, static `FLET_COUNTER`):
  NOT fixed names, because a JVM let-init lambda that captures a same-named
  outer variable miscompiles (see todo-062);
  unique names avoid same-name nesting entirely. Counter values differ between
  the analyzers'/interpreter's throwaway expansions and the compile one -- fine,
  every generated name is bound inside its own expansion (macroexpand output
  diverges across backends like gensym, so no ci-spec macroexpand case). The
  counter value also depends on HOW MUCH the macro-time evaluator evaluates,
  which is demand-driven since macro-time globals went lazy
  (`.kb/defmacro-backquote.md`): a `defvar` init that no macro reads no longer
  bumps it. Same license -- names renumber, nothing rebinds -- but it means two
  otherwise-identical compilers can emit different temp names, so a renumbering
  in a diff is not by itself evidence of a behavior change. What IS a bug is a
  compiler whose output varies between JVM RUNS of the same jar; see
  `.kb/emitted-output-determinism.md`.
- **Lambda lists are desugared in the expansion** via `LambdaLists.expand` (the
  native "required + &rest" shape + let* prologue): `LambdaLists.desugarProgram`
  does not look inside flet definition lists, and `FreeVarAnalyzer.extractParamNames`
  chokes on raw `(b 10)` optionals; desugaring here also puts defaults into
  expression position for the body rewrite.
- **Def-name validation**: duplicate names and names in
  `PackageRegistry.specialOperatorNames()` are `IllegalArgumentException`s
  (locally shadowing an ordinary built-in function like `list` is allowed and
  works).
- Wiring: `expandBuiltinMacro` + evaluator + Jvm/WasmExprCompiler +
  NoGcWasmCompiler.expandMacro (--no-gc then fails on the lambda, as before) +
  FreeVarAnalyzer (both walks, expand-before-walking) + a UserMacroExpander
  `expandAll` case (def names/lambda lists kept so a user macro of the same name
  is not expanded there).
- Interpreter-only caveat: the rewrite happens before user-macro expansion at
  eval time, so a user macro called in an flet body receives already-rewritten
  argument forms; a macro that treats an argument as *data* (quotes it) sees
  `(funcall __flet0_f ...)` instead of `(f ...)`. The compile path is unaffected
  (UserMacroExpander runs first). `macrolet`/`symbol-macrolet` stay in
  `.todo/034-local-function-definition.md`.
