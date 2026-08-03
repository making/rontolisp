# make-broadcast-stream

`(make-broadcast-stream &rest streams)`

An output stream that fans every write out to each component stream, in the order
given. With no components it is the discarding sink instead: writes to it are
dropped (the CL idiom for a null output stream).

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
so exactly the operators that dispatch on one work with it:
[`format`](../macros/format.md), [`princ`](princ.md), [`prin1`](prin1.md),
[`write-string`](write-string.md) and `write-char`.
[`terpri`](terpri.md), [`fresh-line`](fresh-line.md),
[`write-line`](write-line.md), [`print`](print.md),
[`force-output`](force-output.md), [`finish-output`](finish-output.md) and
[`close`](close.md) are not part of that protocol here and signal on any Gray
stream, broadcast or not -- write a newline with `(format s "~%")` instead. The
component-less sink is an ordinary string output stream and takes all of them.
