# i81. ASDF: a component type spelled as a class symbol (`(my-class "name")`)

Difficulty: Medium

Real ASDF reads a component's type through `class-for-type`, so any symbol naming a `component`
subclass is a type, not only `:file`/`:module`/`:static-file`. Both loaders refuse it (measured
2026-11-06, interpreter and JVM, cl-unification 20260101):

    system cl-unification: each component must be (:file "name" ...), (:module "dir" ...) or
    (:static-file "name"), got (ASDF-SYSTEM-DEFINITION-FILE "cl-unification-lib")

`cl-unification.asd` defines the class in the same file and loads an `.asd` as a source file:

    (defclass asdf-system-definition-file (asdf:cl-source-file) ())
    (defmethod source-file-type ((c asdf-system-definition-file) (s module)) "asd")
    ... :components (... (asdf-system-definition-file "cl-unification-lib"))

The class and the `source-file-type` method are already understood (`.kb/asdf.md`, "The
component-class surface"); only the component grammar rejects the non-keyword head. Loading
`cl-unification-lib.asd` as a component then runs a `defsystem` inside a component load, which
must register (measure). With the component removed by hand the library loads and `unify`,
`match`, `apply-substitution` answer what SBCL 2.2.9 answers.

Done when: cl-unification loads unpatched on all four, a ci-spec case pins a class-symbol
component type (the class defined in the `.asd`).
