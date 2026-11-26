# pathname-host

`(pathname-host pathname)`

The host component of a pathname -- always `nil` here. A rontolisp namestring is
a flat, Unix-shaped path with no host syntax, so the component is not present,
and `nil` is what Common Lisp prescribes for a component that is not there.

The argument is still validated as a pathname designator, exactly as
[`namestring`](namestring.md) validates it, so a non-designator signals rather
than answering `nil`. [`pathname-device`](pathname-device.md) and
[`pathname-version`](pathname-version.md) are the two siblings with the same
answer for the same reason.

```lisp
(pathname-host "d/a.txt")   ; => NIL
```

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
