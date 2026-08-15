# `package` is a `defmethod` specializer

Difficulty: Low

Part of `.todo/372` (rove).

`(typep x 'package)` / a `typecase` `package` clause already work: `makeTypeTest`
answers "a keyword naming a registered package", the runtime package model of
`.kb/symbol-runtime-api.md` (a package value IS `find-package`'s keyword; landed
for cl-package-locks' `resolve-package`). `defmethod` does not know the type:
`isSupportedTypeSpecializer` (`LispMacroExpander`, next to `PATHNAME`) lacks
`PACKAGE`, so

```lisp
(defgeneric find-suite (package)
  (:method ((package package))
    (values (gethash package *package-suites*)))
  (:method (package-name)
    (check-type package-name string-designator)
    (let ((package (find-package package-name)))
      (unless package (error "No package '~A' found" package-name))
      (find-suite package))))
```

(rove core/suite/package.lisp) dies at load with "DEFMETHOD: unknown
specializer PACKAGE (a class must be defined by defclass before the method)".
Rove's whole suite registry (`package-suite`, `set-test`, `setup`, `defhook`) is
behind this generic.

Add `PACKAGE` as a TYPE specializer with the same test the type test uses, and
rank it: more specific than `SYMBOL` and `KEYWORD` (a keyword that names a
package must reach the `package` method before a `symbol`/`t` method -- rove's
second method is the DESIGNATOR fallback that calls `find-package` and recurses;
misordered it recurses forever). Note in `.kb/clos.md`'s specializer list and in
`.kb/symbol-runtime-api.md`'s package paragraph that the type and the specializer
share one definition; the re-evaluation trigger already written there (a
consumer needing package objects distinct from keywords) covers both.

Acceptance: the shape above dispatching `(find-suite :pkg)`, `(find-suite "PKG")`,
`(find-suite 'pkg)` to the right method on all four backends (per-backend suites
+ a `ci-spec.yaml` case; a `check-type` over an unregistered `deftype` name is
skipped, so the fixture is faithful to rove).
