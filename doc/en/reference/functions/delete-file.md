# delete-file

`(delete-file pathname)`

Deletes the named file and returns `t`. Anything that leaves the file in place is
an error -- including "it was not there to begin with", which Common Lisp also
makes a `file-error`. Probe first with [`probe-file`](probe-file.md) when a
missing file should be tolerated, or wrap the call in `ignore-errors`.

Works on all four backends. Both WASM backends unlink through the `path_unlink_file` WASI import (Preview 1 directly, `--component` through `wasi:filesystem`'s `unlink-file-at`); a missing file answers the same `file-error` everywhere. An empty directory is deleted too, on every backend (on WASM through `path_remove_directory`, `--component` through `remove-directory-at`); a non-empty one stays and signals the `file-error`.

```console
(with-open-file (out "notes.txt" :direction :output)
  (write-line "draft" out))
(delete-file "notes.txt")   ; => T
(probe-file "notes.txt")    ; => NIL
(delete-file "notes.txt")   ; signals: DELETE-FILE: cannot delete notes.txt
```
