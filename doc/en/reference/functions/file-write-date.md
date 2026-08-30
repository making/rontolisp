# file-write-date

`(file-write-date pathname)`

The file's last-modification time as a [universal time](get-universal-time.md) (seconds since 1900-01-01 GMT), or `nil` when it cannot be determined — which is what a missing or unreadable file answers. Like [`probe-file`](probe-file.md) it never signals, so it can be used as a probe. The path is interpreted exactly as `open` interprets it.

**Both WASM backends always answer `nil`**: the timestamp is not lifted there. Unlike [`file-length`](file-length.md), which stats a descriptor already in hand, this one names a PATH, so it would need an open/stat/close of its own. `nil` is precisely Common Lisp's answer for "the time cannot be determined", so a portable caller's unknown-time fallback runs rather than the program failing. The interpreter and the JVM answer for real.

```console
(let ((stamp (file-write-date "config.lisp")))
  (if stamp
      (print (decode-universal-time stamp))
      (print "unknown")))
```
