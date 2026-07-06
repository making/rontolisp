# defun

`(defun name (params...) body...)`

Defines a function named `name` in the function namespace, with the given parameter list and body, and returns the name symbol. The `body` is not evaluated at definition time; it runs on each call, returning the value of the last body form. Per Lisp-2 the definition lives in the function namespace, so the name is reachable in call position (and via `#'name`) without colliding with any like-named variable.

```lisp
(defun sq (x) (* x x)) ; => sq
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
(make-point :y 5) ; => (0 5 t)
```

An unknown keyword argument signals an error unless the lambda list declares `&allow-other-keys` or the caller passes `:allow-other-keys t`. `&aux` introduces auxiliary variables bound like a trailing `let*`. `&whole` is not supported.

```lisp
(defun area (w &optional (h w) &aux (a (* w h)))
  a)
(area 3) ; => 9
```

Calling a function with too few required arguments (or too many, for a fixed-arity function) signals an error in the interpreter and is a compile error on the JVM/WASM backends.

```console
> (defun f (a b) (+ a b))
> (f 1)
Function expects 2 arguments, got 1
```

## setf-function names

The `name` may be a `(setf name)` list instead of a plain symbol. This defines a *setf-function*: the writer invoked when `name` is used as a `setf` place. The new value is passed as the first argument (it is the last required parameter of the setf lambda list, per the Common Lisp convention), so `(setf (name arg...) value)` calls the writer with `value` followed by `arg...`. The function is also first-class through `#'(setf name)`.

```lisp
(defvar *mode* :xml)
(defun (setf my-mode) (m) (setq *mode* m))
(setf (my-mode) :html5)
*mode* ; => :html5
```

Only the `(setf name)` form is supported (a two-element list); `symbol-function`/`fboundp` of a `(setf ...)` name is not.
