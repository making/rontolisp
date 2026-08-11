# boundp

`(boundp symbol)`

Returns `t` when `symbol` names a bound **global** variable (`defvar`/`defparameter`/top-level `setq`), nil otherwise. Like Common Lisp's dynamic-only `boundp`, lexical bindings (`let`, function parameters) are invisible. `t`, `nil` and keywords are self-evaluating constants, so `boundp` of any of them is `t`.

On the compiled backends a `boundp` of a LITERAL symbol is answered at compile time and costs nothing: a compiled program cannot make a global appear at run time, so the definitions before the call decide the answer. A computed symbol (`(boundp (intern name))`) is resolved against the embedded eval runtime's global environment instead, and pulls that runtime into the output like `eval` does — as [`symbol-value`](symbol-value.md) and [`fboundp`](fboundp.md) do whatever their argument is. A program compiled with `--dynamic`, or one that calls `eval`/`load`, keeps the run-time check throughout.

```lisp
(defvar *level* 7)
(boundp '*level*) ; => T
```

```lisp
(boundp '*undefined-var*) ; => NIL
```

```lisp
(boundp :key) ; => T
```

```lisp
(let ((x 1)) (boundp 'x)) ; => NIL
```
