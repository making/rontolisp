# if

`(if test consequent)` `(if test consequent alternate)`

Evaluates `test`; if its value is anything but `#f`, evaluates `consequent`, otherwise `alternate`. The empty list and `0` are true. With no `alternate` and a false test the value is unspecified: it writes as `#!unspecific` and the REPL shows nothing.

```scheme
(if (> 3 2) 'yes 'no) ; => yes
(if '() 'true 'false) ; => true
(list (if #f #f)) ; => (#!unspecific)
```
