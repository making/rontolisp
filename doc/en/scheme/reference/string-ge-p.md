# string>=?

`(string>=? string1 string2 string3 ...)`

Returns `#t` if the strings are non-increasing in lexicographic order, otherwise `#f`. At least two arguments are required.

```scheme
(string>=? "b" "a") ; => #t
(string>=? "a" "b") ; => #f
```
