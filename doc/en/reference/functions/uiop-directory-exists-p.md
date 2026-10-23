# uiop:directory-exists-p

`(uiop:directory-exists-p pathname)`

Answers whether a *directory* exists: its truename (with a trailing `/`, symbolic
links resolved, as [`probe-file`](probe-file.md) answers it) when it does, `nil`
when it does not or when the pathname names a file. The directory twin of
[`uiop:file-exists-p`](uiop-file-exists-p.md), and libraries use it to validate a
directory root before walking it.

It is also what tells an EMPTY directory from a missing one:
[`directory`](directory.md) answers `nil` for both.

```lisp
(uiop:directory-exists-p "definitely-missing-dir")   ; => NIL
```

## Backend support

All four backends, over [`probe-file`](probe-file.md) -- so a host without a
filesystem (the browser playground) answers `nil` rather than failing, and a WASM
module answers `nil` for everything unless it was run with `--dir`.
