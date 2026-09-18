# string<=?

`(string<=? string1 string2 string3 ...)`

Returns `#t` if the strings are non-decreasing in lexicographic order, otherwise `#f`. At least two arguments are required.

```scheme
(string<=? "a" "a" "b") ; => #t
(string<=? "b" "a") ; => #f
```
