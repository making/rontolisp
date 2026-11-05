# pprint-exit-if-list-exhausted

`(pprint-exit-if-list-exhausted)`

Inside a [`pprint-logical-block`](pprint-logical-block.md), leaves the block when its list has no element left, and otherwise answers `nil`. Outside a block it is an error.

```lisp
(with-output-to-string (s)
  (pprint-logical-block (s '(1 2 3) :prefix "<" :suffix ">")
    (loop (princ (pprint-pop) s)
          (pprint-exit-if-list-exhausted)
          (write-string "," s)))) ; => "<1,2,3>"
```
