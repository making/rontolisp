# rename-file

`(rename-file file new-name)`

Renames (moves) `file` to `new-name` and returns the defaulted new name as a
pathname. `new-name` is merged with `file` the way
[`merge-pathnames`](merge-pathnames.md) merges, so a bare file name keeps the
original directory. Anything that leaves the file where it was signals -- "it
was not there" included, exactly like [`delete-file`](delete-file.md).

```console
CL-USER> (rename-file "notes.txt" "notes.bak")
#P"notes.bak"
CL-USER> (rename-file "db/2026.up.sql" "2026.down.sql")
#P"db/2026.down.sql"
```

Lite deviation: Common Lisp returns `(values defaulted-new-name old-truename
new-truename)` and this returns the defaulted new name only -- the same rule
[`ensure-directories-exist`](ensure-directories-exist.md) follows, because a
secondary value would not survive the function boundary on the compiled
backends.

## Backend support

Interpreter and JVM rename for real. Both WASM backends signal at CALL time: the
WASI import set here carries no rename call, and "the file is at the new name
afterwards" has no honest non-answer -- the same divergence
[`delete-file`](delete-file.md) and
[`ensure-directories-exist`](ensure-directories-exist.md) have.
