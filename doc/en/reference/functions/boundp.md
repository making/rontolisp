# boundp

`(boundp symbol)`

Returns `t` when `symbol` names a bound **global** variable (`defvar`/`defparameter`/top-level `setq`), nil otherwise. Like Common Lisp's dynamic-only `boundp`, lexical bindings (`let`, function parameters) are invisible. `t`, `nil` and keywords are self-evaluating constants, so `boundp` of any of them is `t`.

On the compiled backends the check reads the embedded eval runtime's global environment, so using `boundp` (or [`symbol-value`](symbol-value.md)/[`fboundp`](fboundp.md)) pulls that runtime into the output like `eval` does.

```lisp
(defvar *level* 7)
(boundp '*level*) ; => t
```

```lisp
(boundp '*undefined-var*) ; => nil
```

```lisp
(boundp :key) ; => t
```

```lisp
(let ((x 1)) (boundp 'x)) ; => nil
```
