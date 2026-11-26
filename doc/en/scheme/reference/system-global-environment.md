# system-global-environment

`system-global-environment`

A variable holding MIT Scheme's other name for the global environment, for use as the second argument of `eval`. It is the same object as `user-initial-environment`; it writes as `#[environment]`. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(eval '(* 2 3) system-global-environment) ; => 6
(eq? system-global-environment user-initial-environment) ; => #t
```
