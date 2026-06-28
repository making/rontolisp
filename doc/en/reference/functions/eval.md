# eval

`(eval form)`

Evaluates a form -- typically a list produced by `read`, `read-from-string`, or quoting -- in the global environment and returns its result. Works in all three backends: the interpreter evaluates directly, while the JVM and WASM compilers emit a small runtime tree-walking interpreter that shares the compiled value representation. Top-level globals defined by the compiled program are mirrored into the eval environment, so an eval'd expression can see them.

```lisp
(eval '(+ 1 2)) ; => 3
```

Quoting the form keeps it unevaluated until `eval` runs it; `(eval (read-from-string "(* 6 7)"))` returns `42`.
