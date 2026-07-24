# string=

`(string= string1 string2 &key start1 end1 start2 end2)`

Compares two strings character by character and returns `t` when they are exactly equal, `nil` otherwise. The comparison is case-sensitive, so `"abc"` and `"ABC"` are not equal; use `string-equal` for a case-insensitive test. `:start1`/`:end1`/`:start2`/`:end2` bound the substrings actually compared.

```lisp
(list (string= "abc" "abc") (string= "together" "frog" :start1 1 :end1 3 :start2 2)) ; => (T T)
```
