# asdf:test-system

`(asdf:test-system name)`

Loads the named system, follows its `:in-order-to ((test-op (test-op ...)))`
chain (loading and testing each chained system the same way), then runs the
system's recorded `:perform (test-op (o c) ...)` body with the operation
parameter bound to `nil` (there is no `operate` machinery) and the component
parameter bound to the system's metaobject
([`asdf:find-system`](asdf-find-system.md)'s answer). Returns `t`. A system
with no test-op wiring is a no-op, like real ASDF's default `perform`.

This is the standard entry point fukamachi-style `.asd`s ship:

```console
(defsystem "my-app"
  :components ((:file "main"))
  :in-order-to ((test-op (test-op "my-app/tests"))))

(defsystem "my-app/tests"
  :depends-on ("my-app" "rove")
  :components ((:file "tests/main"))
  :perform (test-op (op c) (symbol-call :rove :run c)))

;; run the tests:
(asdf:test-system "my-app")
```

The body is recorded as data when the `.asd` is parsed; its bare symbols
resolve the way `asdf-user` would resolve them (`symbol-call` is
`uiop:symbol-call`, `component-name` is `asdf:component-name`). A `:perform`
with a method qualifier (`test-op :after (o c)`) or a `#.` reader macro in its
body stays tolerated-and-ignored, as all test-op wiring used to be.

## Backend support

Works on all four backends. On the compile paths a **literal, top-level**
`(asdf:test-system NAME)` splices the system *and* its test-op chain at compile
time (a plain `load-system` never pulls the tests system in), then runs the
recorded bodies at run time; a nested/computed call can only reach systems the
program already spliced.
