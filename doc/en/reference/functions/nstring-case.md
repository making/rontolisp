# nstring-upcase nstring-downcase nstring-capitalize

`(nstring-upcase string)` -- `(nstring-downcase string)` -- `(nstring-capitalize string)`

The destructive spellings of [`string-upcase`](string-upcase.md), [`string-downcase`](string-downcase.md) and [`string-capitalize`](string-capitalize.md): the folded characters are written back into the argument, and the string is returned. The fold is the non-destructive sibling's, so the returned value is the same on every backend.

The write is real for any string the running program allocated -- what [`make-string`](make-string.md) and `(make-array n :element-type 'character)` build, and equally a `copy-seq`/[`subseq`](subseq.md) slice or a `concatenate 'string` / [`string-upcase`](string-upcase.md)-family / `format nil` / [`with-output-to-string`](../macros/with-output-to-string.md) / [`read-line`](read-line.md) result: the very same object comes back, and a caller holding its own reference sees the change.

```lisp
(let ((s (make-string 3 :initial-element #\a)))
  (list (eq s (nstring-upcase s)) s))
; => (T "AAA")
```

For a string **literal** -- and, on the compiled backends, for the few remaining producers whose results are still immutable values there ([`princ-to-string`](princ-to-string.md), for example) -- the fold lands on a rebuilt string rather than a write, which is the deviation every indexed write (`(setf (aref s i) c)`, [`replace`](replace.md), [`fill`](fill.md)) has for those strings. Portable code uses the returned value, which is correct on all four backends whatever the argument was:

```lisp
(nstring-upcase (copy-seq "hello world")) ; => "HELLO WORLD"
```

The whole string is folded: like their non-destructive siblings these take no `:start` / `:end`. Each is a first-class function value, so `#'nstring-upcase` can be passed to `funcall`, `mapcar` or `intern`.

## Backend support

All four backends -- one definition in rontolisp source, spliced into the program when it is referenced.
