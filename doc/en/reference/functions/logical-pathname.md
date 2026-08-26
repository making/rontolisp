# logical-pathname

`(logical-pathname pathspec)`

Always signals. Common Lisp requires an error unless the argument is a logical
pathname or a logical-pathname namestring, and rontolisp can define no logical
host, so no argument can satisfy that. Answering a physical pathname instead
would claim a translation table exists.

```console
CL-USER> (logical-pathname "SYS:SRC;")
LOGICAL-PATHNAME: "SYS:SRC;" does not name a logical pathname (rontolisp defines no logical hosts)
```

Use [`pathname`](pathname.md) to build the physical pathname a namestring names,
and [`translate-logical-pathname`](translate-logical-pathname.md) -- the
identity here -- where portable code normalizes before opening.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
