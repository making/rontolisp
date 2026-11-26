# string-ci=?

`(string-ci=? string1 string2 string3 ...)`

Like `string=?`, but compares the strings after `string-foldcase`, so `ß` equals `ss`. At least two arguments are required.

```scheme
(string-ci=? "Hello" "hELLO") ; => #t
(string-ci=? "Straße" "STRASSE") ; => #t
(string-ci=? "a" "b") ; => #f
```
