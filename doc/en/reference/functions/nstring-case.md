# nstring-upcase nstring-downcase nstring-capitalize

`(nstring-upcase string)` -- `(nstring-downcase string)` -- `(nstring-capitalize string)`

The destructive spellings of [`string-upcase`](string-upcase.md), [`string-downcase`](string-downcase.md) and [`string-capitalize`](string-capitalize.md): the folded characters are written back into the argument, and the string is returned. The fold is the non-destructive sibling's, so the returned value is the same on every backend.

The write is real for a **mutable character vector** -- what [`make-string`](make-string.md) and `(make-array n :element-type 'character)` build: the very same object comes back, and a caller holding its own reference sees the change.

```lisp
(let ((s (make-string 3 :initial-element #\a)))
  (list (eq s (nstring-upcase s)) s))
; => (T "AAA")
```

For an **immutable** string the compiled backends rebuild rather than write, which is the deviation every indexed write (`(setf (aref s i) c)`, [`replace`](replace.md), [`fill`](fill.md)) has there -- so the caller's own reference is left alone while the interpreter's is folded. Portable code uses the returned value, which is correct on all four backends.

```lisp
(nstring-upcase (copy-seq "hello world")) ; => "HELLO WORLD"
```

The whole string is folded: like their non-destructive siblings these take no `:start` / `:end`. Each is a first-class function value, so `#'nstring-upcase` can be passed to `funcall`, `mapcar` or `intern`.

## Backend support

All four backends -- one definition in rontolisp source, spliced into the program when it is referenced.
