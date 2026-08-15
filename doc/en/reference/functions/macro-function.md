# macro-function

`(macro-function symbol &optional environment)`

The macro expander of `symbol`, or `nil` when the name is a function, one of the 25 [special operators](special-operator-p.md), or unknown. It answers non-nil for a user macro defined with [`defmacro`](../special-forms/defmacro.md), for every built-in macro (the names `rontolisp:list-macros` reports), and for the Common Lisp macros rontolisp implements as special forms of its own (`defun`, `handler-case`, `dolist`, ...) -- together, every name a caller may not `apply`.

The `environment` argument is accepted and ignored: `macrolet` bodies are expanded away before any body runs, so the global answer is the only one there is.

```lisp
(defmacro greet (x) `(list :hello ,x))
(list (and (macro-function 'greet) t) (and (macro-function 'when) t)
      (macro-function 'car) (macro-function 'if)) ; => (T T NIL NIL)
```

On the interpreter the value is the real expander -- a single-step expansion callable as `(funcall expander form environment)`:

```lisp
(funcall (macro-function 'when) '(when t 1) nil) ; => (IF T 1 NIL)
```

A COMPILED program has no macro table left (macros are fully expanded before the backends see the program), so there the value is a stub: the predicate above is exact on all four backends, but calling it signals `macro-function: a compiled program cannot expand a macro at run time`.

The `setf` place is supported for one shape only: `(setf (macro-function 'new) (macro-function 'existing))` gives an existing `defmacro`-defined macro a second name sharing its expander, so both names expand identically from then on. Anything else -- an arbitrary expander function, or a name that is not a user macro -- signals an error, because there is no macro function object to store.

```lisp
(defmacro greet2 (x) `(list :hello ,x))
(setf (macro-function 'hi) (macro-function 'greet2))
(hi "world") ; => (:HELLO "world")
```

Lite: a non-symbol argument answers `nil` where Common Lisp signals a type error.
