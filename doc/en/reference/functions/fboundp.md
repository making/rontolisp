# fboundp

`(fboundp function-name)`

Returns `t` when `function-name` names something callable or expandable: a function (built-in or `defun`), a macro (built-in or `defmacro`), a special form, or a `car`/`cdr` composition like `cadr`. This matches Common Lisp, where `fboundp` is true of macros and special operators too. A `(setf name)` list is a function name too, quoted or built at run time: `t` when a `(setf name)` function is defined.

On the compiled backends a **literal** quoted argument is decided at compile time with full knowledge (macros and special forms included); a computed argument is checked at runtime against the function registries, which only know real functions — so `(fboundp (intern "cond"))` is nil in compiled code but `t` in the interpreter, and `defmacro` macros are likewise compile-time-only there.

As a function value (`#'fboundp`, as in `(mapcar #'fboundp names)`) it is a computed probe on every backend, with the run-time answers above; the eval runtime is pulled in only for a program that names `#'fboundp`.

`t`, `nil` and keywords are symbols that name no function, so the answer is `nil` for them on every backend.

A name retired by [`fmakunbound`](fmakunbound.md) answers `nil` again, at a literal call site too.

A function a [`defun`](../special-forms/defun.md#below-the-top-level) below the top level defines is bound once that definition has run: a literal argument answers `nil` before and `t` after on every backend. A computed argument naming it answers `nil` on the compiled backends.

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

```lisp
(defun (setf fb-first) (value list) (setf (car list) value))
(list (fboundp '(setf fb-first)) (fboundp '(setf fb-none))) ; => (T NIL)
```
