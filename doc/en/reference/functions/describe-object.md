# describe-object

`(describe-object object stream)`

The generic function [`describe`](describe.md) calls. Its system method writes the object as `prin1` does and, on the next line, its type in brackets, the first two lines SBCL writes; the rest of a description is implementation-dependent (CLHS) and is not written. A method on it, for a class or as an `:after` method on every object, joins that one generic whatever package defines it.

```lisp
(subseq (with-output-to-string (s) (describe-object "abc" s)) 0 5) ; => "\"abc\""
```
