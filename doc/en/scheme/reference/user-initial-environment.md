# user-initial-environment

`user-initial-environment`

A variable holding MIT Scheme's name for the global environment, for use as the second argument of `eval`. There is only one global environment, so it is the same object as `system-global-environment`, `(interaction-environment)` and every `(environment ...)`; it writes as `#[environment]`. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(eval '(+ 1 2) user-initial-environment) ; => 3
user-initial-environment ; => #[environment]
```
