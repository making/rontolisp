# eval

`(eval expr)` `(eval expr environment)`

Evaluates the datum `expr` at run time and returns its value. Every environment specifier -- `(interaction-environment)`, `(environment ...)`, `(scheme-report-environment 5)`, `user-initial-environment`, `system-global-environment` -- names the one global environment, and the argument may be left out (R7RS requires it). A `define` inside `eval` creates a program global. `define-record-type`, `define-values`, `let-values` and `import` are refused inside `eval`. See [eval](../eval.md) for the full semantics.

```scheme
(eval '(+ 1 2) (interaction-environment)) ; => 3
(eval '(* 2 3)) ; => 6
```
