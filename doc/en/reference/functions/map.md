# map

`(map result-type function &rest sequences)`

Applies `function` to successive elements of the given `sequences` -- each of which may be a list, a vector or a string -- and builds a result of the requested type. When several sequences are given, the function is called with one element from each and iteration stops at the end of the shortest sequence. `'list` and `'cons` collect the results into a list, `'string` builds a string from the character results, `'vector` builds a general vector, and `nil` calls the function only for its side effects and returns nil. A vector `result-type` spelling an element type builds what [`coerce`](coerce.md) builds for it: `'(vector (unsigned-byte 8))` a specialized vector, `'(vector single-float)` a packed float array, `'(vector character)` a string. A zero-parameter [`deftype`](../macros/deftype.md) name means what it expands to. A computed `result-type` dispatches at run time as `coerce` does.

```lisp
(map 'list #'+ '(1 2 3) '(10 20 30)) ; => (11 22 33)
```

```lisp
(map 'string #'char-upcase "abc") ; => "ABC"
(array-element-type (map '(vector (unsigned-byte 8)) #'char-code "ab")) ; => (UNSIGNED-BYTE 8)
```
