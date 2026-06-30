# map

`(map result-type function &rest sequences)`

Applies `function` to successive elements of the given `sequences` -- each of which may be a list or a string -- and builds a result of the requested type. When several sequences are given, the function is called with one element from each and iteration stops at the end of the shortest sequence. `result-type` must be written as a literal designator (resolved statically, like `concatenate`): `'list` collects the results into a list, `'string` builds a string from the character results, and `nil` calls the function only for its side effects and returns nil.

```lisp
(map 'list #'+ '(1 2 3) '(10 20 30)) ; => (11 22 33)
```

```lisp
(map 'string #'char-upcase "abc") ; => "ABC"
```
