# scheme-report-environment

`(scheme-report-environment version)`

Returns an environment for `eval`. Every environment specifier denotes the one global environment, `#[environment]`, whatever `version` is. It belongs to `(scheme r5rs)` and is visible only to a file with no `import`. See [eval](../eval.md).

```scheme
(scheme-report-environment 5) ; => #[environment]
(eval '(* 6 7) (scheme-report-environment 5)) ; => 42
```
