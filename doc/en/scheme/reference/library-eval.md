# (scheme eval)

Evaluating a datum at run time. See [eval](../eval.md) for what an environment is here.

| Name | Example | Result |
|---|---|---|
| `eval` | `(eval '(+ 1 2) (interaction-environment))` | `3` |
| `environment` | `(environment '(scheme base))` | `#[environment]` |
