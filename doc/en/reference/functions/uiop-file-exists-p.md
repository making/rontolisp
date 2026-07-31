# uiop:file-exists-p

`(uiop:file-exists-p pathname)`

Answers whether a file exists: the pathname when it does, `nil` when it does
not. It is exactly [`probe-file`](probe-file.md) under its ASDF/UIOP name --
same contract, same behavior -- and it lowers onto that primitive on every
backend, so libraries spelling the question the UIOP way (postmodern's
`execute-file`, for instance) need no shim.

A pathname is its namestring in rontolisp, so the "truename" returned on success
is the argument string itself: no backend resolves symbolic links or makes the
path absolute. A directory counts as existing.

```lisp
(uiop:file-exists-p "definitely-missing.txt")   ; => NIL
```

## Backend support

Works on all four backends. The interpreter registers a global function
delegating to `probe-file`; the compile paths rewrite the one-argument call into
a direct `probe-file` call.
