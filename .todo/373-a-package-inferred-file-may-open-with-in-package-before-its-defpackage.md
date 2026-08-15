# A package-inferred file may open with `(in-package #:cl-user)` before its `defpackage`

Difficulty: Low

Part of `.todo/372` (rove); the first thing `(ql:quickload "rove")` hits.

`AsdfSystems.deriveSubSystem` reads ONE form (`LispReader.readFirstForm`) and
`packageDependencies` requires it to be a `defpackage`/`define-package`:

```
Error: .../rove-20260101-git/core/assertion.lisp: the first form of a
package-inferred system's file must be a DEFPACKAGE (its dependencies are read
from there), got (IN-PACKAGE #:CL-USER)
```

Real ASDF (`asdf/package-inferred-system::file-defpackage-form`) reads forms
until the first `package-definition-form-p` and ignores everything before it;
the `(in-package #:cl-user)` header before a `defpackage` is a common style
(rove's core/assertion.lisp, core/result.lisp, core/stats.lisp,
core/suite.lisp, core/suite/package.lisp, reporter.lisp, reporter/spec.lisp,
misc/*.lisp all have it). Match ASDF: skip forms until a `defpackage` /
`define-package` head; keep "no defpackage at all" the same hard error, now
naming the file and saying no package definition form was found.

Verified shape (spike, 2026-08-15): a `LispReader.readFirstFormMatching(source,
features, file, predicate)` beside `readFirstForm` -- same tolerant lexer mode,
provenance recording OFF, loop `readExpr` until the predicate holds -- and
`deriveSubSystem` passing the defpackage/define-package test. With that one
change rove's whole graph derives and loads (the next stop is `.todo/374`).

Acceptance: `AsdfSystemsTest` case with an `in-package` + `defpackage` file
(and one with two leading forms) deriving its dependencies; `.kb/asdf.md`
package-inferred paragraph updated ("Only the first form of each file is read"
in `doc/{en,ja}/guides/asdf-systems.md` becomes "the first package definition
form"); `PackageInferredSystemE2eTest`'s fixture gains the `in-package` header
so all four backends pin it.
