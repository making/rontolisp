# time

`(time form)`

Evaluates `form`, prints a line of the form `; Elapsed real time: N ms` to standard output, and returns the value `form` produced. `N` is an integer of milliseconds on the interpreter and JVM backends and a float of milliseconds on WASM; because it reports wall-clock time it is non-deterministic and varies from run to run. The example below prints a timing line and returns `3`.

```lisp
(time (+ 1 2))
```
