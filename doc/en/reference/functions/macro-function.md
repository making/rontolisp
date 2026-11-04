# macro-function

`(macro-function symbol &optional environment)`

The macro expander of `symbol`, or `nil` when the name is a function, one of the 25 [special operators](special-operator-p.md), or unknown. It answers non-nil for a user macro defined with [`defmacro`](../special-forms/defmacro.md), for every built-in macro, and for the Common Lisp macros rontolisp implements as special forms of its own (`defun`, `handler-case`, `dolist`, ...) -- together, every name a caller may not `apply`. A Common Lisp FUNCTION rontolisp happens to expand inline (`typep`, `format`, `error`, `make-instance`, ...) answers `nil`, as does `declare`.

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

`(setf (macro-function 'name) function)` makes `function` the expander of `name`: a call expands to what `(funcall function form nil)` answers, `form` being the whole call. `macro-function` then answers that same function. A value that is not a function, or a name that is not a symbol, signals a `type-error`.

```lisp
(setf (macro-function 'twice)
      (lambda (form env) (declare (ignore env)) `(* 2 ,(second form))))
(twice 21) ; => 42
```

A compiled program has no macro table, so there the write happens at compile time and leaves nothing in the program, like a `defmacro`. The value form runs at compile time and sees what is defined by then. The form must be at top level, as a member of a `progn` or `eval-when` (a macro may build it), or the body of a `let` or `flet` around it. A value form that fails stops the compile, naming the form, and so does a write anywhere else, such as in a function body. The interpreter accepts it anywhere.

`(setf (macro-function 'new) (macro-function 'existing))` gives an existing macro a second name, so both names expand identically from then on: a `defmacro`-defined macro shares its expander, and a built-in macro such as `incf` is reached by rewriting the call. The new name may be a keyword (`(setf (macro-function :+) (macro-function 'incf))`). A name that is not a macro signals an error.

```lisp
(defmacro greet2 (x) `(list :hello ,x))
(setf (macro-function 'hi) (macro-function 'greet2))
(hi "world") ; => (:HELLO "world")
```

Lite: a non-symbol argument answers `nil` where Common Lisp signals a type error.
