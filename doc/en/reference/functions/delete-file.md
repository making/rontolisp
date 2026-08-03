# delete-file

`(delete-file pathname)`

Deletes the named file and returns `t`. Anything that leaves the file in place is
an error -- including "it was not there to begin with", which Common Lisp also
makes a `file-error`. Probe first with [`probe-file`](probe-file.md) when a
missing file should be tolerated, or wrap the call in `ignore-errors`.

**Both WASM backends signal at call time.** No WASI unlink call is imported
there, and unlike [`file-write-date`](file-write-date.md) this operation has no
"cannot be determined" answer in its contract: either the file is gone afterwards
or it is not, so answering anything but an error would be a lie. This is the same
deal [`ensure-directories-exist`](ensure-directories-exist.md) makes, for the
same reason -- a program merely CONTAINING the call still compiles there; only
executing it signals.

```console
(with-open-file (out "notes.txt" :direction :output)
  (write-line "draft" out))
(delete-file "notes.txt")   ; => T
(probe-file "notes.txt")    ; => NIL
(delete-file "notes.txt")   ; signals: DELETE-FILE: cannot delete notes.txt
```
