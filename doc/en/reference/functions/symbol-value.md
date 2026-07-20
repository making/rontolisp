# symbol-value

`(symbol-value symbol)`

Returns the value of the **global** variable named by `symbol`; an unbound name signals an error (a trap on WASM). Like Common Lisp's dynamic-only `symbol-value`, lexical bindings are invisible. `t`, `nil` and keywords evaluate to themselves. Use [`boundp`](boundp.md) to test first, and [`intern`](intern.md) to build the name at runtime.

```lisp
(defvar *level* 7)
(symbol-value '*level*) ; => 7
```

```lisp
(symbol-value (intern "*LEVEL*")) ; => 7
```

```lisp
(symbol-value :key) ; => :KEY
```

An unbound variable signals an error:

```console
> (symbol-value '*nope*)
The variable *nope* is unbound
```
