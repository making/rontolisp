# asdf:operate

`(asdf:operate operation system &rest keys)`

Performs an operation on a system, as real ASDF's entry point does. With
`asdf:load-op` or `asdf:load-source-op` it is [`asdf:load-system`](asdf-load-system.md)
(the keys are passed along and ignored like `load-system`'s); with `asdf:test-op` it is
[`asdf:test-system`](asdf-test-system.md); with `asdf:build-op` it is `asdf:make`, which
performs the system's `:build-operation`; with `asdf:program-op` (or a subclass, such as
deploy's `deploy-op`) it loads the system and builds its executable
([Build an Executable from an ASDF System](../../compiling/asdf-programs.md)). Any other
operation signals: a system is loaded from source here, so there is nothing for
`compile-op` to produce on its own, and no bundle is built.
[`asdf:oos`](asdf-oos.md) is the old name. `operation` is a class name or an operation instance
(see [`asdf:perform`](asdf-perform.md)); `system` is a name designator or the
system's metaobject. Returns the operation instance.

```console
(asdf:operate 'asdf:load-op :my-lib)   ; same as (asdf:load-system :my-lib)
(asdf:operate 'asdf:test-op "my-app")  ; same as (asdf:test-system "my-app")
```

## Backend support

Works on all four backends. On the compile paths a top-level call with a quoted
`load-op` and a literal name splices the system, like a literal `asdf:load-system`, and with
`test-op` like a literal `asdf:test-system`. Elsewhere `load-op` answers for a system the
program already spliced and signals for any other, like a run-time `load-system`. An
executable is built only by the interpreter, which the build runs in: in a compiled program
`program-op` signals, naming `rontolisp build`.
