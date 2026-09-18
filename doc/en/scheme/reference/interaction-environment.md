# interaction-environment

`(interaction-environment)`

Returns the environment specifier of the running program, for `eval`. It is the one global environment every specifier names, printed as `#[environment]`: it holds the program's variables and procedures and the built-in procedures.

```scheme
(interaction-environment) ; => #[environment]
(eval '(+ 1 2) (interaction-environment)) ; => 3
```
