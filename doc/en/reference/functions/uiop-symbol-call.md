# uiop:symbol-call

`(uiop:symbol-call package name &rest arguments)`

UIOP's late-binding call: look `name` up in `package` at run time and apply it
to `arguments`. Both are designators -- a keyword, a symbol or a string. This is
how a library calls into a system it does not depend on and may not have loaded,
which is why it is spelled with a run-time lookup rather than a direct call.

```lisp
(uiop:symbol-call :cl :+ 1 2 3) ; => 6
```

A package that does not exist, or a name that package does not have, signals --
the caller is about to apply the result, so an absent name is an error rather
than a `nil` that fails one frame later.

It is also a function value, which is how the idiom is usually written: a
library dispatching to one of several backends applies `#'uiop:symbol-call` to
the arguments it was handed, and names the backend with uninterned symbols so
that reading the form cannot require the package to exist.

```lisp
(defpackage :backend-a (:use :cl) (:export :request))
(in-package :backend-a)
(defun request (uri &rest args) (list uri args))
(in-package :cl-user)
(apply #'uiop:symbol-call '#:backend-a '#:request "http://x" '(:method :get))
; => ("http://x" (:METHOD :GET))
```

Each arm of such a dispatch is resolved on its own, so an arm naming a backend
this program does not have costs a call-time error if it ever runs, and nothing
at all otherwise.

## Backend support

- **Interpreter**: full support (the lookup runs against the live package and
  function tables).
- **JVM** and **WASM**: full support -- the call is late-bound through the
  compiled name registry, like `funcall` of a runtime-interned symbol, and
  `#'uiop:symbol-call` is a value like any other function. An absent package
  still signals; an absent *name* signals at the call (the undefined-function
  error) rather than at the lookup, slightly later than the interpreter's own
  probe but just as loud.
