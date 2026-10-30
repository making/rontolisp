# asdf:operate

`(asdf:operate operation system &rest keys)`

Performs an operation on a system, as real ASDF's entry point does. With
`asdf:load-op` it is [`asdf:load-system`](asdf-load-system.md) (the keys are
passed along and ignored like `load-system`'s); with `asdf:test-op` it is
[`asdf:test-system`](asdf-test-system.md). Any other operation signals: a
system is loaded from source here, so there is nothing for `compile-op` to
produce on its own. `operation` is a class name or an operation instance
(see [`asdf:perform`](asdf-perform.md)); `system` is a name designator or the
system's metaobject. Returns the operation instance.

```console
(asdf:operate 'asdf:load-op :my-lib)   ; same as (asdf:load-system :my-lib)
(asdf:operate 'asdf:test-op "my-app")  ; same as (asdf:test-system "my-app")
```

## Backend support

Works on all four backends. On the compile paths `load-op` answers for a
system the program already spliced and signals for any other, like a run-time
`load-system`.
