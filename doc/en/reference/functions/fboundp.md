# fboundp

`(fboundp symbol)`

Returns `t` when `symbol` names something callable or expandable: a function (built-in or `defun`), a macro (built-in or `defmacro`), a special form, or a `car`/`cdr` composition like `cadr`. This matches Common Lisp, where `fboundp` is true of macros and special operators too.

On the compiled backends a **literal** quoted argument is decided at compile time with full knowledge (macros and special forms included); a computed argument is checked at runtime against the function registries, which only know real functions — so `(fboundp (intern "cond"))` is nil in compiled code but `t` in the interpreter, and `defmacro` macros are likewise compile-time-only there.

```lisp
(fboundp 'car) ; => T
```

```lisp
(fboundp 'cond) ; => T
```

```lisp
(defun greet (n) n)
(fboundp 'greet) ; => T
```

```lisp
(fboundp 'no-such-fn) ; => NIL
```
