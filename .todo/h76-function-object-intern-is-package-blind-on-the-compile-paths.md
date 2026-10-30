# h76. The function object `#'intern` / `#'find-symbol` is package-blind on the compile paths

Difficulty: Medium

Split from `.todo/h66`, which fixed the CALL `(intern x)` / `(find-symbol x)`.

```lisp
(defpackage :susf (:use :cl))
(in-package :susf)
(defvar *a* (mapcar #'intern '("DATA" "MORE")))
(defvar *b* (funcall 'intern "DATA"))
(in-package :cl-user)
(print (list susf::*a* susf::*b*))
; SBCL, interpreter: ((SUSF::DATA SUSF::MORE) SUSF::DATA)
; JVM, P1, component: ((DATA MORE) DATA)
```

`compiler/CurrentPackageIntern` rewrites only call forms of the user program (in
`CompileFrontend`, before any library splice). The wrapper (`BuiltinFunctionWrappers`,
`unaryOptionalSecond(INTERN)` and `findSymbolWrapper`) is injected after package resolution
and its 1-arg branch builds the bare spelling (`.kb/symbol-runtime-api.md`, "1-arg `intern`").

## What the fix needs

The wrapper's 1-arg branch must read `*package*` in a program that switches packages, while
the implementation's own 1-arg interns (prelude, `scheme.lisp`, `format-render-slash.lisp`,
the lowerings) keep interning a canonical spelling verbatim. Two routes:

- Rewrite `#'intern` / `#'find-symbol` (and a quoted designator in a function position,
  which `FunctionDesignators.normalizeBuiltinDesignators` turns into `#'name` later) in
  `CurrentPackageIntern` into a lambda passing `cl:*package*`.
- Give the internal verbatim-spelling uses their own operator, then let every 1-arg
  `intern` lowering read `*package*` in a switching program (wrapper included).

Acceptance: the repro on all four backends, the ci-spec case
`intern-of-an-internal-name-is-the-read-symbol` extended, the function-object sentence in
`doc/{en,ja}/reference/functions/intern.md` removed.
