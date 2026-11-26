# make-string

`(make-string size &key initial-element element-type)`

Returns a fresh string of `size` characters, each equal to `:initial-element` (default a space; the standard leaves the fill character implementation-defined). `:element-type` is accepted and ignored -- rontolisp has a single string representation. The result is a MUTABLE buffer on every backend: [`replace`](replace.md), `(setf (char ...))` and `(setf (subseq ...))` write into it in place, and the write is visible through every reference to it. Available on all backends except `--no-gc`.

```lisp
(make-string 3 :initial-element #\x) ; => "xxx"
```

```lisp
(let ((buf (make-string 5)))
  (replace buf "ab")
  (replace buf "cde" :start1 2)
  buf) ; => "abcde"
```
