# asdf:perform

`(asdf:perform operation component)`

The generic function every action of a system load goes through, as in real
ASDF. [`asdf:load-system`](asdf-load-system.md) performs, in this order:
`prepare-op` on the system; `prepare-op`, `compile-op` and `load-op` on each of
its component files in load order; then `compile-op` and `load-op` on the
system. A method a library adds therefore runs around every component load —
the `:around` method of asdf-flv (the file-local variables fiveam uses) binds
its variables around each file, so an assignment one file makes stays in that
file.

```console
(defmethod asdf:perform :around ((o asdf:load-op) (c asdf:cl-source-file))
  (progv *file-local-variables* (mapcar #'symbol-value *file-local-variables*)
    (call-next-method)))
```

The operation is an instance of `asdf:operation` or one of its subclasses
`asdf:prepare-op`, `asdf:compile-op`, `asdf:load-op` and `asdf:test-op`;
`asdf:make-operation` answers the one instance of a class, and an operation
designated by its class name (`(asdf:perform 'asdf:load-op c)`) means that
instance. The default methods are real ASDF's for the classes there are:
`load-op` on an `asdf:cl-source-file` loads the source file, and `compile-op`
on a source file does nothing — there are no fasls, so a source file is
compiled by loading it. On a system (any parent component) every operation
does nothing; `test-op` on a system runs the `:perform (test-op (o c) ...)`
body its `.asd` recorded ([`asdf:test-system`](asdf-test-system.md)).
A method in the `.asd` itself is not run: the `.asd` is read as data.

`asdf:input-files` and `asdf:output-files` are generic functions too:
`compile-op` and `load-op` on a source file read its pathname, and no
operation writes a file (`output-files` answers `nil`). `asdf:source-file-type`
exists so a library may define methods on it (lass does); the extension of a
component is decided when the `.asd` is parsed. `asdf/interface` (the name
real ASDF gives the `asdf` package itself) and real ASDF's internal packages
(`asdf/action`, `asdf/component`, `asdf/lisp-action`, ...) name these same
symbols.

## Backend support

Works on all four backends, in the same order. A compiled program has no load
to wrap — its systems were spliced at compile time — so once the program
defines an `asdf:perform` method, each component spliced after the definition
is compiled as a unit of its own that runs as the thunk its `load-op` is
performed with: the forms are still top-level forms (definitions are collected
where they stand), but an `:around` method runs around them. Before the
definition, and in a program that defines no method, a component's forms run
in sequence as they always do. A method on a compiled backend applies from
program start (method dispatch is static there), so a component loaded between
two method definitions sees both. `--no-gc` refuses a program that would need
such a unit. Performing `load-op` on a component at run time anywhere else
signals on the compiled backends, like a run-time `load-system`.
