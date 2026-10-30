# h66. A one-argument `intern` / `find-symbol` is package-blind on the compile paths

Difficulty: Medium

Split from `.todo/h55` (2026-10-30), which fixed the two-argument build.

```lisp
(defpackage :susm (:use :cl))
(in-package :susm)
(defvar *a* (intern "DATA"))
(in-package :cl-user)
(print (list susm::*a* (eq susm::*a* 'susm::data)))
; SBCL, interpreter: (SUSM::DATA T)
; JVM, P1, component: (DATA NIL)
```

The compiled 1-arg `intern` (`JvmSymbolApiCompiler.compileIntern`'s `_internName`, WASM
`_intern_sym`) and the 1-arg `find-symbol` fold build a bare spelling: `cl-user`'s
(`.kb/symbol-runtime-api.md`, "1-arg `intern`").

## What the fix needs

CL reads `*package*` when the call RUNS. The compile paths keep `*package*` live only when
the program reads it (`injectMvSpillGlobal`; otherwise the `in-package` assignments are
dropped, `.kb/packages.md` "`*package*` is a DYNAMIC variable"). So: rewrite
`(intern x)` -> `(intern x *package*)` (and `find-symbol`) BEFORE the prelude selection and
`injectBakedAccess`'s site scan see the program, in a program that switches to a non-`cl-user`
package -- the rewrite's mention makes `*package*` live. The site then takes the computed
designator path (`%symbol-in-package`, `%program-package-p`, every program package's row).

## Weigh first

Every program with an `in-package` and a run-time 1-arg `intern` (library helpers that
survive pruning) would pay the `*package*` variable, its assignments and the computed-lookup
tables. Measure the examples sweep before choosing between that and a narrower fold (a
top-level form's current package is static: `(intern x)` there could fold to the literal
package).

Acceptance: the repro on all four backends, a ci-spec case, `doc/{en,ja}/reference/functions/intern.md`'s
one-argument deviation sentence removed.
