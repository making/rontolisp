# i99. Compile paths: `:import-from` of a name only a consumed `defmacro` interns

Difficulty: Medium

A `defpackage` importing a symbol that its source package's files only define as a macro fails
on every compile path (measured 2026-11-07, JVM, Preview 1, component; the interpreter runs it):

    (defpackage :imp-p1 (:use :cl) (:export #:visible))
    (in-package :imp-p1)
    (defmacro hidden* () 1)
    (defun visible () 2)
    (in-package :cl-user)
    (defpackage :imp-p2 (:use :cl) (:import-from :imp-p1 #:hidden*))
    (print (list (imp-p2::hidden*) (imp-p1:visible)))   ; SBCL, interpreter: (1 2)

    error: :IMPORT-FROM: no symbol named HIDDEN* in IMP-P1

`UserMacroExpander` consumes the `defmacro` before the backend's `PackageResolver.resolveProgram`
runs, so nothing mints `hidden*` there and `imp-p1` stays sealed (`PackageRegistry.sealed`);
`collectImportFrom` then answers "no such symbol". A `defun` of the same name works. serapeum's
`package.lisp` imports `serapeum/iter::collecting*` this way, so this is where the JVM compile of
serapeum (and spinneret) stops now (`.kb/asdf.md`, `eval/TrivialArgumentsForms`). The same compile
also prints `library top-level form failed at compile time (skipped): The function
%DEFINE-METHOD-COMBINATION is undefined`; find which form and whether it matters.

Decide how a consumed definition's name reaches the backend's resolver: a trace the expander leaves
that the resolver reads and drops, or the import check consulting what the compile-time evaluator
interned. Mind what a runtime `find-symbol` of the macro's name answers on the compile paths (the
interpreter answers the symbol) and the emitted-output tests that a new top-level form would touch.

Done when: the program above prints `(1 2)` on all four backends, and the serapeum JVM compile gets past
`package.lisp` (record where it stops next).
