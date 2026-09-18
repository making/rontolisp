# Scheme: `case-lambda` (`(scheme case-lambda)`)

Difficulty: Medium

Split off from `.todo/826`. R7RS 4.2.9 `case-lambda`: a procedure choosing the first
clause whose formals accept the argument count.

Scope:

- Lower to core forms only (no backend change): a `&rest` lambda dispatching on the
  argument count, each clause's formals bound from the rest list.
- A new library tag `case-lambda`, importable as `(import (scheme case-lambda))`, merged
  into the no-import default, invisible to `(import (scheme base))`; `--scheme-standard r7rs`
  follows the import.
- No clause matching is a Scheme error (catchable by `guard`, reported like the other
  arity errors).
- Interoperates with `define-syntax`/`syntax-rules` (`SchemeExpander` must scope each
  clause's formals), `guard`/`raise`, `parameterize`, `define` of a `case-lambda`.
- `eval` refuses it by name unless trivially supported.

Test plan: failing `SchemeLoweringTest` / `scheme-spec.yaml` cases first (all four
backends, Gauche `gosh -r7` as the oracle), a `standalone:` case for the arity error and
for `(import (scheme base))` not seeing it. Size of a program not using it unchanged
(class/wasm); size of one using it recorded in `.kb/scheme-frontend.md`. Docs: libraries
and deviations pages in `doc/{en,ja}/scheme/`, a reference page plus catalog category.
