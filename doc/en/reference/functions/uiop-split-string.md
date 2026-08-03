# uiop:split-string

`(uiop:split-string string &key max separator)`

Splits `string` into a list of substrings on ANY character of the `separator`
sequence (a string or a character list; the default is space and tab), following
upstream UIOP's semantics: the scan runs right to left, so `:max` bounds the
number of pieces while keeping the UNsplit remainder in the head, and the empty
string yields `("")`. sxql tokenizes dotted column names with
`(uiop:split-string name :separator ".")`.

```lisp
(uiop:split-string "a.b.c" :separator ".")   ; => ("a" "b" "c")
```

```lisp
(uiop:split-string "a.b.c.d.e" :max 3 :separator ".")   ; => ("a.b.c" "d" "e")
```

```lisp
(uiop:split-string "a-b_c" :separator "-_")   ; => ("a" "b" "c")
```

## Backend support

Works on all four backends: it is a prelude definition written in rontolisp
itself and compiled into the program when used.
