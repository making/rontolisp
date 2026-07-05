# find-symbol

`(find-symbol string)`

Like [`intern`](intern.md) but never creates: returns the symbol when the name is already known to the image, nil otherwise. "Known" means a `cl` symbol (function, macro, or special form), a keyword, or a user definition. Deviations from Common Lisp: there is no package argument and no second `status` value, and on the compiled backends (JVM/WASM) the argument must be a **literal** string — the check is folded at compile time against the compile-time view (cl symbols plus the program's own `defun`s), so runtime-defined variables and macros are not visible there (the interpreter checks the live image, including global variables and `defmacro` macros).

```lisp
(find-symbol "car") ; => car
```

```lisp
(find-symbol "cond") ; => cond
```

```lisp
(find-symbol "no-such-name") ; => nil
```

```lisp
(defun greet (n) n)
(find-symbol "greet") ; => greet
```
