# uiop:directory-exists-p

`(uiop:directory-exists-p pathname)`

Answers whether a *directory* exists: the pathname (with a trailing `/`) when it
does, `nil` when it does not. The directory twin of
[`uiop:file-exists-p`](uiop-file-exists-p.md), and libraries use it to validate a
directory root before walking it.

It is also what tells an EMPTY directory from a missing one:
[`directory`](directory.md) answers `nil` for both.

```lisp
(uiop:directory-exists-p "definitely-missing-dir")   ; => NIL
```

## Backend support

All four backends, over the same one primitive [`directory`](directory.md) uses
-- so a host without a filesystem (the browser playground) answers `nil` rather
than failing, and a WASM module answers `nil` for everything unless it was run
with `--dir`.
