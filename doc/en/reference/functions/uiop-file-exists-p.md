# uiop:file-exists-p

`(uiop:file-exists-p pathname)`

Answers whether a file exists: the pathname when it does, `nil` when it does
not or when the pathname names a directory -- upstream UIOP's contract, so
libraries spelling the question the UIOP way (postmodern's `execute-file`, for
instance) need no shim. [`uiop:directory-exists-p`](uiop-directory-exists-p.md)
is the directory twin.

The pathname answered on success is the truename, as
[`probe-file`](probe-file.md)'s is: every symbolic link resolved, nothing made
absolute. A link whose target is missing answers the link itself, as
`probe-file` does.

```lisp
(uiop:file-exists-p "definitely-missing.txt")   ; => NIL
```

## Backend support

Works on all four backends: one definition in rontolisp source over
`probe-file`, which tells a directory by its truename's trailing `/`.
