# string-ci<?

`(string-ci<? string1 string2 string3 ...)`

Like `string<?`, but compares the strings after `string-foldcase`. At least two arguments are required.

```scheme
(string-ci<? "apple" "BANANA") ; => #t
(string-ci<? "B" "a") ; => #f
```
