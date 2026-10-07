# get-output-stream-string

`(get-output-stream-string stream)`

Returns everything written to a `make-string-output-stream` stream so far, and **clears** it: the next call answers only what was written after this one. That is Common Lisp's contract, and it is what lets one accumulator stream be reused for a sequence of tokens. A stream of another kind (a string input stream, a file, a synonym stream, ...) signals a `type-error` whose expected type is `(and string-stream (satisfies output-stream-p))`, and a value that is not a stream one whose expected type is `stream`.

```lisp
(let ((s (make-string-output-stream)))
  (write-string "ab" s)
  (let ((first (get-output-stream-string s)))
    (write-string "cd" s)
    (list first (get-output-stream-string s) (get-output-stream-string s)))) ; => ("ab" "cd" "")
```
