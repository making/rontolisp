# pprint-pop

`(pprint-pop)`

Inside a [`pprint-logical-block`](pprint-logical-block.md), answers the next element of the block's list. It leaves the block instead when the rest is not a list, having printed `. ` and the rest, and when `*print-length*` elements are out, having printed `...`. Past the end of the list, or in a block whose object is `nil`, it answers `nil`, and a body counting through something else still gets `*print-length*`'s truncation. Outside a block it is an error.

```lisp
(with-output-to-string (s)
  (pprint-logical-block (s '(1 2 . 3) :prefix "(" :suffix ")")
    (loop (princ (pprint-pop) s) (write-string " " s)))) ; => "(1 2 . 3)"
```
