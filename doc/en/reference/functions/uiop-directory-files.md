# uiop:directory-files

`(uiop:directory-files pathspec &optional pattern)`

The non-directory entries of a directory: `(directory "<pathspec>/*.*")` with the
subdirectories dropped. `pathspec` names the directory itself (with or without a
trailing `/`) -- the wildcard is supplied here, so this is the "just list it"
spelling.

`pattern` is UIOP's own optional second argument, the namestring of a
name-and-type wildcard. It is appended to the directory and matched exactly as
[`directory`](directory.md) would match it (`*` any sequence, `?` one character),
so `(uiop:directory-files "db/" "*.up.sql")` lists only the up-migrations.
Omitting it lists everything. A pattern carrying a DIRECTORY component is an
error, as it is in real UIOP -- the directory to scan is the first argument's job.

```lisp
(uiop:directory-files "no-such-directory/" "*.up.sql")   ; => NIL
```

## Backend support

All four backends, over the same one primitive `directory` uses.
