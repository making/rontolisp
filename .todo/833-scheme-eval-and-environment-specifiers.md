# Scheme: `eval` with `user-initial-environment` / `(scheme eval)`

Difficulty: High

Split off `.todo/826` (`eval` in the kb's "Not here yet") for `.todo/828`. One corpus file
needs it: `chapter4/section4/subsection4/14_execute.scm`, the query system's `lisp-value`,

```scheme
(define (execute exp)
  (apply (eval (predicate exp) user-initial-environment) (args exp)))
```

where `(predicate exp)` is a SYMBOL read from a query (`>`, `<`) or a `lambda` expression.
The other 14 files with a free `eval` mean the book's own `eval` and are fragments. (The
evaluators' other host hook, `(define apply-in-underlying-scheme apply)` before redefining
`apply`, is `.todo/837`.)

Lowest priority of the `.todo/828` set, filed so the scope is stated:

- The Scheme lowering is Java (`SchemeLowering`) and does not exist inside a compiled
  program; the run-time `eval` every backend carries (`.kb/eval-runtime.md`) evaluates
  CORE forms. So `(eval datum env)` needs a datum -> core-form lowering at RUN time:
  either a Common Lisp port of the expression subset in `scheme.lisp`, or the interpreter
  only with a by-name refusal on the compile path.
- Measure first how far the narrow case goes: a datum that is a symbol naming a builtin
  (the corpus's `>`), or a closed `lambda` over builtins, could lower through a small
  run-time table without a general port. If that covers the corpus, land that and refuse
  the rest by name.
- `user-initial-environment`, `system-global-environment`, `(interaction-environment)`,
  `(scheme-report-environment 5)` are all "the global scope" here; `environment` with
  import sets is a refusal.
