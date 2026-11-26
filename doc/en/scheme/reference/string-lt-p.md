# string<?

`(string<? string1 string2 string3 ...)`

Returns `#t` if the strings are strictly increasing in lexicographic order of their characters' code points, otherwise `#f`. At least two arguments are required. Uppercase letters sort before lowercase ones.

```scheme
(string<? "abc" "abd") ; => #t
(string<? "B" "a") ; => #t
```
