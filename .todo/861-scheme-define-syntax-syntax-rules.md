# Scheme `define-syntax` / `syntax-rules`

Difficulty: High

Split off from `.todo/826` (its first row). Invariant as there: lower to core forms, change
no backend, add the cases to `scheme-spec.yaml` (all four backends).

## Scope

- `define-syntax` (top level and internal), `let-syntax`, `letrec-syntax` with
  `syntax-rules` transformers: literals, `...` (nested, followed by more patterns, in a
  dotted tail), vector patterns, `_`, the custom-ellipsis form
  `(syntax-rules ellipsis (literals) rules ...)`, `(... ...)` escape.
- An expander in `SchemeLowering`, run on datums before desugaring, keyed on the lowering's
  own scope (a local binding of the macro's name shadows it).
- Hygiene: the introduced core keywords already resolve by identity (`CORE_*`). Decide and
  state how far hygiene goes for identifiers a template introduces (free references to
  globals / keywords vs a user's local binding of the same name; binders the template
  introduces must not capture user identifiers). Anything not landed is a follow-up todo.
- Library tag: `(scheme base)` (R7RS 7.1: `define-syntax`, `let-syntax`, `letrec-syntax`,
  `syntax-rules`, `_`, `...`, `syntax-error` are all `(scheme base)`).
- Inside `eval` (`%scheme-eval`): refused by name unless cheap.

## Test plan

- `SchemeLoweringTest`: expansion shapes, shadowing, ellipsis depth errors, refusals.
- `scheme-spec.yaml`: cases for each feature, expected output from Gauche `gosh`, all four
  backends.
- `SchemeSessionTest` / REPL: a macro defined at one prompt used at the next.
- Docs: `doc/{en,ja}/scheme/` syntax page and the "not yet" lists; `.kb/scheme-frontend.md`.
