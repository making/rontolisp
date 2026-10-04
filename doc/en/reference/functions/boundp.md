# boundp

`(boundp symbol)`

Returns `t` when `symbol` names a bound **dynamic** variable -- one with a global value (`defvar`/`defparameter`/top-level `setq`) or an active dynamic binding (a `let` of a special variable, a parameter named like one, `progv`) -- nil otherwise. Like Common Lisp's `boundp`, lexical bindings (a `let` of an ordinary variable, function parameters) are invisible. `t`, `nil` and keywords are self-evaluating constants, so `boundp` of any of them is `t`.

On the compiled backends a `boundp` of a LITERAL symbol is answered at compile time and costs nothing when the definitions before the call decide it: a compiled program cannot make a global appear at run time. A special variable declared without a value is answered at run time, by its variable. A computed symbol (`(boundp (intern name))`) is resolved at run time too, and pulls the embedded eval runtime into the output like `eval` does — as [`symbol-value`](symbol-value.md) and [`fboundp`](fboundp.md) do whatever their argument is. A program compiled with `--dynamic`, or one that calls `eval`/`load`, keeps the run-time check throughout.

As a function value (`#'boundp`, as in `(mapcar #'boundp names)`) it is a computed probe on every backend: the answer comes at run time, and the eval runtime is pulled in only for a program that names `#'boundp`.

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

A special variable declared without a value is bound only for the extent of a binding of it:

```lisp
(defvar *request*)
(list (boundp '*request*) (let ((*request* :r)) (boundp '*request*)) (boundp '*request*)) ; => (NIL T NIL)
```
