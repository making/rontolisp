# ensure-directories-exist

`(ensure-directories-exist pathspec)`

Creates the directory component of `pathspec`, including every missing parent, and returns `pathspec`. The directory component is everything up to and including the last `/`, so `"logs/app.log"` creates `logs/` and leaves the file alone; a namestring that already ends in `/` *is* the directory; a namestring with no `/` names a file in the working directory and creates nothing. An existing directory is not an error.

Lite: Common Lisp returns `(values pathspec created)` and this returns the pathspec only — a second value would not survive the function boundary on the compiled backends, so promising one would be misleading.

**Both WASM backends signal at call time.** No WASI directory-creation call is imported there, and unlike [`file-write-date`](file-write-date.md) this operation has no "cannot be determined" answer in its contract: either the directory exists afterwards or it does not, so answering anything but an error would be a lie.

```console
(ensure-directories-exist "logs/2026/app.log")
(with-open-file (out "logs/2026/app.log" :direction :output)
  (write-line "started" out))
```
