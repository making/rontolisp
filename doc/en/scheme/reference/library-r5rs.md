# (scheme r5rs)

R5RS names that R7RS keeps only in `(scheme r5rs)`. That library is not importable here: these names are visible only to a file with no `import` and at the REPL, and never under [`--scheme-standard r7rs`](../standards.md).

| Name | Example | Result |
|---|---|---|
| `exact->inexact` | `(exact->inexact 1/4)` | `0.25` |
| `inexact->exact` | `(inexact->exact 0.25)` | `1/4` |
| `scheme-report-environment` | `(scheme-report-environment 5)` | `#[environment]` |
