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

## Backend support

- **Interpreter**: full support (the lookup runs against the live package and
  function tables).
- **JVM** and **WASM**: full support -- the call is late-bound through the
  compiled name registry, like `funcall` of a runtime-interned symbol. An
  absent package still signals; an absent *name* signals at the call (the
  undefined-function error) rather than at the lookup, slightly later than the
  interpreter's own probe but just as loud.
