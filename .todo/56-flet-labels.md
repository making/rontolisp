# 56: Phase 3 unit 2 -- flet / labels (local functions)

Part of the ASDF Phase 3 split (see `.todo/54-asdf-support.md`, "Phase 3"
section). Wishlist source: `.todo/34-local-function-definition.md` (this unit
is the `flet`/`labels` half; `macrolet`/`symbol-macrolet` stay in 34 as a
later follow-up).

## Scope

- `flet`: local, non-recursive function bindings in the FUNCTION namespace
  only (Lisp-2 -- call position and `#'name`/`funcall #'name`, not variable
  references). Bodies see the OUTER function bindings.
- `labels`: same but the defined functions see each other (mutual recursion).
- Lambda lists: full `LambdaLists` desugaring applies (required + `&optional`
  /`&rest`/`&key`/`&aux`), same as `defun`.
- `declare` at body head is already harmless after the declarations unit (DONE; see .kb/declarations-type-checks.md) -- it evaluates to nil.

## Design sketch (verify before coding)

The cheapest expansion-level route: rewrite `flet`/`labels` into `let`-bound
lambdas plus a body walk that rewrites (a) call position `(f args...)` ->
`(funcall f-var args...)` and (b) `(function f)`/`#'f` -> `f-var`, scoped by
shadowing (inner flet/labels/let of the same name). This keeps all three
backends untouched (lambdas/closures already work everywhere) but needs a
careful hygienic walker in `LispMacroExpander` (skip `quote`, handle nested
binders, gensym the vars so the variable namespace is not polluted).
For `labels`, bind vars to nil first, then `setq` each to its lambda (the
lambdas close over the vars, so mutual recursion works -- same trick the
letrec lowering uses everywhere).

Alternative (if the walker gets hairy): per-backend local function
environments -- much more work (three backends). Start with the expansion
route.

## Wiring checklist

Same as any macro: `LispNames`, `PackageRegistry.CL_MACROS`,
`expandBuiltinMacro`, evaluator + Jvm/Wasm compileCons + ScalarWasmCompiler +
`FreeVarAnalyzer` cases, list-macros expectations + ci-spec introspection
update, native E2E, docs (en+ja) + catalog, `.kb` note.
