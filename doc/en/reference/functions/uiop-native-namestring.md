# uiop:native-namestring

`(uiop:native-namestring pathname)`

The pathname's namestring in the host operating system's own spelling. A
rontolisp namestring already IS the host spelling -- no backend translates
between a Lisp and a native syntax -- so this is `namestring`. Libraries call
it where a path leaves Lisp (jzon stringifies a pathname value through it,
trivial-mimes hands one to an external probe).

```lisp
(uiop:native-namestring #P"/tmp/data.json")   ; => "/tmp/data.json"
```

## Backend support

All four backends: the interpreter as a built-in, the compile paths lowered
onto `namestring`.
