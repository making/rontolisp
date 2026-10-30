# readtable-case

`(readtable-case readtable)`

The case a readtable converts the unescaped letters of a token to: `:upcase` (the standard readtable's), `:downcase`, `:preserve` or `:invert` (a token whose unescaped letters are all one case is inverted, any other one kept). `(setf (readtable-case readtable) mode)` sets it. Works on all four backends; only the interpreter's reader converts by it.

```lisp
(readtable-case *readtable*) ; => :UPCASE
```

```lisp
(let ((*readtable* (copy-readtable nil)))
  (setf (readtable-case *readtable*) :invert)
  (list (read-from-string "foo") (read-from-string "Foo")))
; => (FOO |Foo|)
```
