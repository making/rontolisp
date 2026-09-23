# make-broadcast-stream

`(make-broadcast-stream &rest streams)`

An output stream that fans every write out to each component stream, in the order
given. With no components it is a broadcast stream over an empty list instead:
writes to it are dropped (the CL idiom for a null output stream), and the file
queries answer for nothing -- `file-length` 0, `file-position` 0,
`file-string-length` 1, `stream-external-format` `:default`. With components the
same queries answer the last component.

```lisp
(let ((a (make-string-output-stream))
      (b (make-string-output-stream)))
  (let ((s (make-broadcast-stream a b)))
    (format s "sync ~A" 42))
  (list (get-output-stream-string a) (get-output-stream-string b))) ; => ("sync 42" "sync 42")
```

```lisp
(let ((s (make-broadcast-stream)))
  (write-string "discarded" s)
  :done) ; => :DONE
```

A broadcast stream WITH components is a [Gray stream](../../guides/gray-streams.md),
so it takes the whole output protocol: [`format`](../macros/format.md),
[`princ`](princ.md), [`prin1`](prin1.md), [`print`](print.md),
[`write-string`](write-string.md), `write-char`, [`terpri`](terpri.md),
[`fresh-line`](fresh-line.md), [`write-line`](write-line.md),
[`force-output`](force-output.md), [`finish-output`](finish-output.md),
[`clear-output`](clear-output.md) and [`close`](close.md).
`fresh-line` answers the last component, or nil with no components.
