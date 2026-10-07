# defun

`(defun name (params...) body...)`

Defines a function named `name` in the function namespace, with the given parameter list and body, and returns the name symbol. The `body` is not evaluated at definition time; it runs on each call, returning the value of the last body form. Per Lisp-2 the definition lives in the function namespace, so the name is reachable in call position (and via `#'name`) without colliding with any like-named variable.

```lisp
(defun sq (x) (* x x)) ; => SQ
```

```lisp
(defun sq (x) (* x x))
(sq 6) ; => 36
```

## Lambda list keywords

The parameter list supports the Common Lisp lambda-list keywords `&optional`, `&rest`, `&key`, `&allow-other-keys`, and `&aux` (in that order). A default form is evaluated only when the argument is absent, and it can reference parameters bound to its left. An optional or keyword parameter may declare a supplied-p variable that is `t` when the caller passed the argument.

```lisp
(defun greet (name &optional (greeting "Hello"))
  (concatenate 'string greeting ", " name))
(greet "world" "Hi") ; => "Hi, world"
```

```lisp
(defun sum (&rest xs)
  (reduce #'+ xs :initial-value 0))
(sum 1 2 3 4) ; => 10
```

```lisp
(defun make-point (&key (x 0) (y 0 y-supplied-p))
  (list x y y-supplied-p))
(make-point :y 5) ; => (0 5 T)
```

An unknown keyword argument signals an error unless the lambda list declares `&allow-other-keys` or the caller passes `:allow-other-keys t`. `&aux` introduces auxiliary variables bound like a trailing `let*`. `&whole` is not supported.

```lisp
(defun area (w &optional (h w) &aux (a (* w h)))
  a)
(area 3) ; => 9
```

Calling a function with too few required arguments (or too many, for a fixed-arity function) evaluates the arguments and then signals a catchable `program-error` when the call runs, on every backend. It is not a compile error: the JVM/WASM compilers print a warning for a direct call whose count is wrong, which `--warnings-as-errors` turns into a failed compile ([Compile-Time Warnings](../../compiling/warnings.md)).

```console
CL-USER> (defun f (a b) (+ a b))
CL-USER> (f 1)
Function expects 2 arguments, got 1
```

A call through a function *value* -- `funcall`, `apply`, `mapcar`, a variable holding `#'f` -- signals the same `program-error` with the same text.

```lisp
(defun f (a b) (+ a b))
(handler-case (apply #'f '(1)) (program-error (c) (princ-to-string c)))
; => "Function expects 2 arguments, got 1"
```

A built-in operator's function value names the operator in place of `Function`.

```lisp
(handler-case (funcall #'cons 1) (program-error (c) (princ-to-string c)))
; => "CONS expects 2 arguments, got 1"
```

A direct call of a built-in operator with an argument count its lambda list rules out behaves the same way, and names the operator too.

```lisp
(handler-case (car '(1 2) 2) (program-error (c) (princ-to-string c)))
; => "CAR expects 1 argument, got 2"
```

A function whose lambda list ends in `&optional` parameters (no `&rest` or `&key`) takes
at most its required plus optional count: a surplus argument signals the same catchable
`program-error` at run time, on every backend, before any default form is evaluated.

```lisp
(defun g (a &optional b) (list a b))
(handler-case (g 1 2 3) (program-error (c) (princ-to-string c)))
; => "Function expects at most 2 arguments, got 3"
```

## Special parameters

A parameter whose name is proclaimed special (by [`defvar`](defvar.md)/[`defparameter`](defparameter.md) or `(declaim (special ...))`), or which a `(declare (special ...))` at the head of the body names, is bound **dynamically**, as a [`let`](let.md) of it would be: a function called during the body sees the argument, a default form sees the binding of a parameter to its left, and the previous value is restored when the call exits, however it exits. A closure built in the body reads the binding in effect when it is called, as every reference to a special does. This holds for every section of the lambda list, supplied-p variables included, and for [`lambda`](lambda.md), `flet` and `labels` alike, on every backend. A call in the body of such a function is not a tail call -- the binding is undone after it returns -- so a function that recurses through one uses stack for each call.

```lisp
(defvar *scale* 1)
(defun scaled (n) (* n *scale*))
(defun scaled-by (*scale* n) (scaled n))
(list (scaled-by 10 5) (scaled 5)) ; => (50 5)
```

## Below the top level

A `defun` inside a function body or over a `let` defines the function when that form runs. Until then the name is undefined on every backend: [`fboundp`](../functions/fboundp.md) answers `nil`, and a call, `#'name` or `symbol-function` signals `undefined-function` naming the function. A name built at run time (`intern`, `read-from-string`, `eval`) reaches the function as a literal one does, and [`fmakunbound`](../functions/fmakunbound.md) retires it for every reference, a direct call included, until the definition runs again.

```lisp
(defun install () (defun late (x) (* x 2)))
(fboundp 'late) ; => NIL
(handler-case (late 1) (undefined-function (c) (cell-error-name c))) ; => LATE
(install)
(list (fboundp 'late) (late 4)) ; => (T 8)
```

## setf-function names

The `name` may be a `(setf name)` list instead of a plain symbol. This defines a *setf-function*: the writer invoked when `name` is used as a `setf` place. The new value is passed as the first argument (it is the last required parameter of the setf lambda list, per the Common Lisp convention), so `(setf (name arg...) value)` calls the writer with `value` followed by `arg...`. The function is also first-class through `#'(setf name)`.

```lisp
(defvar *mode* :xml)
(defun (setf my-mode) (m) (setq *mode* m))
(setf (my-mode) :html5)
*mode* ; => :HTML5
```

Like any `defun`, a setf-function may be defined below the top level -- over a `let`, or inside a function body, defined when that runs. Only the `(setf name)` form is supported (a two-element list); [`fdefinition`](../functions/fdefinition.md), `fboundp` and `fmakunbound` take it, `symbol-function` does not (a symbol only, as in Common Lisp).
