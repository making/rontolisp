# string-equal

`(string-equal string1 string2 &key start1 end1 start2 end2)`

Compares two strings character by character ignoring case and returns `t` when they match, `nil` otherwise. Case folding follows ASCII rules, so `"ABC"` and `"abc"` are equal. Use `string=` for a case-sensitive comparison. `:start1`/`:end1`/`:start2`/`:end2` bound the substrings actually compared.

```lisp
(list (string-equal "ABC" "abc") (string-equal "TOGETHER" "frog" :start1 1 :end1 3 :start2 2)) ; => (T T)
```
