# ignore-errors

`(ignore-errors form...)`

Evaluates the forms and returns the value of the last one; when an error is signaled, returns nil instead (with the condition object as the syntactic-tier secondary value). Sugar over `(handler-case (progn form...) (error (c) (values nil c)))` — see [`handler-case`](handler-case.md).

Like `handler-case` it is supported on **every backend** except `--no-gc`; runtime traps remain uncatchable on the wasm-GC backends — see [`handler-case`](handler-case.md).

```lisp
(ignore-errors (error "boom")) ; => NIL
```

```lisp
(ignore-errors (+ 1 2)) ; => 3
```
