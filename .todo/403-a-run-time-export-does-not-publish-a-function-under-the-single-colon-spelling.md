# 403. A run-time `export` does not publish a function under the `pkg:name` spelling

Difficulty: Medium

Found while building the dexador spike's stand-in shims (`.todo/396`). A
function defined on an internal symbol and then exported is not reachable
through the external spelling:

```lisp
(defpackage :spike (:use :cl))
(defun spike::my-fn (x) (* x 2))
(export '(spike::my-fn) :spike)
(spike:my-fn 21)          ; => The function SPIKE:MY-FN is undefined
(spike::my-fn 21)         ; => 42
```

`export` accepted the symbol; the double-colon spelling still works; only the
single-colon spelling misses. So the function namespace is keyed on the
SPELLING the `defun` used rather than on the symbol `export` published, and
`export` does not re-key what is already bound. Same on a Java-seeded package
(`asdf`, `babel`) as on a user `defpackage`, so it is not a seeding artefact.

## Why it matters

`defpackage` + `defun` + a later `(export ...)` is an everyday CL idiom -- a
file defines its functions and exports them at the end, or a shim adds a name
to a package it does not own. Any library written that way loses its whole
public surface with no error at definition time and an "undefined function"
at the call, which reads as a missing feature rather than as our bug.

It also blocks the natural workaround shape for `.todo/398`: a `.lisp`-only
widening of a Java-seeded shim package cannot publish its new names, which is
why that item has to touch `PackageRegistry`.

## The work

- `.kb/packages.md` + `.kb/symbol-runtime-api.md` for the current model, and
  `.todo/156` (the symbol/intern-table redesign) for the direction -- decide
  whether this is a fix inside the present model (make `export` re-key the
  function and value cells, or make lookup canonicalize `pkg:name` to the
  home-package spelling before probing) or a case the redesign must cover.
  Canonicalizing at LOOKUP is the smaller and more honest of the two: a symbol
  has one identity and its accessibility is a property of the package, not of
  the string a caller typed.
- The same question applies to the value cell (`defvar` on `pkg::x` then
  `export`) and to `setf` functions -- check and fix together, do not fix the
  function cell alone.
- Both compile paths resolve names at compile time, so the fix has a
  compile-path half; a program that exports at load time and calls through the
  external spelling in a later top-level form is the test shape.
- Pin on all four backends.
