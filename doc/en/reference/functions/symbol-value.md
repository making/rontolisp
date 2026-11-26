# symbol-value

`(symbol-value symbol)`

Returns the current value of the **dynamic** (global) variable named by `symbol`: the innermost dynamic binding -- a `let` of a special variable, a parameter named like one, `progv` -- when one is active, else the global value; an unbound name signals an `unbound-variable` naming it, and so does a special variable declared without a value (`(defvar *x*)`) outside every binding of it (see [`cell-error-name`](cell-error-name.md); on WASM a program with no catching form traps instead). Like Common Lisp's `symbol-value`, lexical bindings are invisible. `t`, `nil` and keywords evaluate to themselves. Use [`boundp`](boundp.md) to test first, and [`intern`](intern.md) to build the name at runtime.

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
CL-USER> (symbol-value '*nope*)
The variable *nope* is unbound
```
