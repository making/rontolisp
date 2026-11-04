# asdf:oos

`(asdf:oos operation system &rest keys)`

The old name of [`asdf:operate`](asdf-operate.md), with the same arguments and the
same answer: `(asdf:oos 'asdf:load-op :my-lib)` is a load. The other spellings of a load
are `(asdf:load-systems :a :b)`, `(asdf:load-systems* '(:a :b))` and
`(asdf:require-system :a)`. `(asdf:make :a)` runs the system's `:build-operation`, by
default a load; any other build operation is refused, and so is `asdf:compile-system`,
because no image is dumped and no fasl is written here.

```console
(asdf:oos 'asdf:load-op :my-lib)   ; same as (asdf:load-system :my-lib)
(asdf:oos 'asdf:test-op "my-app")  ; same as (asdf:test-system "my-app")
```

## Backend support

Works on all four backends. On the compile paths a top-level call with a literal name
splices the system, like a literal `asdf:load-system`; a call anywhere else answers for
a system the program already spliced and signals for any other.
