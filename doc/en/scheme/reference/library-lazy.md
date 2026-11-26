# (scheme lazy)

Promises: the `delay` and `delay-force` syntax and the procedures over their values.

| Name | Example | Result |
|---|---|---|
| `delay` | `(force (delay (* 6 7)))` | `42` |
| `delay-force` | `(force (delay-force (delay (+ 1 2))))` | `3` |
| `force` | `(force (delay (+ 1 2)))` | `3` |
| `make-promise` | `(force (make-promise 42))` | `42` |
| `promise?` | `(promise? (delay 1))` | `#t` |
