# string=?

`(string=? string1 string2 string3 ...)`

Returns `#t` if all the strings have the same length and the same characters, otherwise `#f`. At least two arguments are required; the comparison is case-sensitive.

```scheme
(string=? "abc" "abc" "abc") ; => #t
(string=? "abc" "ABC") ; => #f
```
