# get-output-stream-string

`(get-output-stream-string stream)`

Returns everything written to a `make-string-output-stream` stream so far, and **clears** it: the next call answers only what was written after this one. That is Common Lisp's contract, and it is what lets one accumulator stream be reused for a sequence of tokens.

```lisp
(let ((s (make-string-output-stream)))
  (write-string "ab" s)
  (let ((first (get-output-stream-string s)))
    (write-string "cd" s)
    (list first (get-output-stream-string s) (get-output-stream-string s)))) ; => ("ab" "cd" "")
```
