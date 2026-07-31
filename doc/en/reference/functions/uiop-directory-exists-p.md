# uiop:directory-exists-p

`(uiop:directory-exists-p pathname)`

Answers whether a *directory* exists: the pathname (with a trailing `/`) when it
does, `nil` when it does not. The directory twin of
[`uiop:file-exists-p`](uiop-file-exists-p.md), and libraries use it to validate a
directory root before walking it.

Note what rontolisp does **not** offer: there is no way to LIST a directory.
`uiop:collect-sub*directories` and `uiop:directory-files` resolve as names but
signal when called, because reading and writing a *named* file is the whole
filesystem surface rontolisp exposes on any backend -- and the WASM backends have
no filesystem at all.

```lisp
(uiop:directory-exists-p "definitely-missing-dir")   ; => NIL
```

## Backend support

Interpreter only. The probe goes through the same source-loader abstraction as
`probe-file`, so a host without a filesystem (the browser playground) answers
`nil` rather than failing. On the JVM and the WASM backends the call compiles to a
run-time error, like the other `uiop:` members with no cross-backend meaning.
