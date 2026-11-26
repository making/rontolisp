# pathname-device

`(pathname-device pathname)`

The device component of a pathname -- always `nil` here, for the reason
[`pathname-host`](pathname-host.md) is: a flat namestring carries no device.
That is also what SBCL answers on Unix.

```lisp
(pathname-device #P"d/a.txt")   ; => NIL
```

Portable code tests the answer against both `nil` and `:unspecific` before using
it, so a `nil` device simply means "there is no device component to account
for".

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
