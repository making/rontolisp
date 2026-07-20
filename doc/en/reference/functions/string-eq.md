# string=

`(string= string1 string2)`

Compares two strings character by character and returns `t` when they are exactly equal, `nil` otherwise. The comparison is case-sensitive, so `"abc"` and `"ABC"` are not equal; use `string-equal` for a case-insensitive test.

```lisp
(string= "abc" "abc") ; => T
```
