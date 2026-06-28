# string-equal

`(string-equal string1 string2)`

Compares two strings character by character ignoring case and returns `t` when they match, `nil` otherwise. Case folding follows ASCII rules, so `"ABC"` and `"abc"` are equal. Use `string=` for a case-sensitive comparison.

```lisp
(string-equal "ABC" "abc") ; => t
```
