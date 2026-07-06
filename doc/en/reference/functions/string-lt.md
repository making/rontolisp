# string<

`(string< string1 string2)`

Case-sensitive lexicographic comparison. Returns the index of the first differing character position when `string1` is ordered strictly before `string2`, or `nil` otherwise. Each argument is coerced with `string`, so a symbol designator is accepted.

```lisp
(string< "abc" "abd") ; => 2
```
