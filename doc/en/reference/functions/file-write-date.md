# file-write-date

`(file-write-date pathname)`

The file's last-modification time as a [universal time](get-universal-time.md) (seconds since 1900-01-01 GMT), or `nil` when it cannot be determined — which is what a missing or unreadable file answers. Like [`probe-file`](probe-file.md) it never signals, so it can be used as a probe. The path is interpreted exactly as `open` interprets it.

Works on all four backends. Both WASM backends stat the path through the `path_filestat_get` WASI import (Preview 1 directly, `--component` through `wasi:filesystem`'s `stat-at`), following symbolic links like the other two. A `--no-wasi` module has no files, so there it answers `nil`.

```console
(let ((stamp (file-write-date "config.lisp")))
  (if stamp
      (print (decode-universal-time stamp))
      (print "unknown")))
```
