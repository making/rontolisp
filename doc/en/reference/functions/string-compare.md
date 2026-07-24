# string< string> string<= string>= string/= string-lessp string-greaterp string-not-greaterp string-not-lessp string-not-equal

`(string< string1 string2 &key start1 end1 start2 end2)` -- `(string> ...)` -- `(string<= ...)` -- `(string>= ...)` -- `(string/= ...)` -- `(string-lessp ...)` -- `(string-greaterp ...)` -- `(string-not-greaterp ...)` -- `(string-not-lessp ...)` -- `(string-not-equal ...)`

Compare two strings lexicographically and return the mismatch index (a true value) when the relation holds, `nil` otherwise. The index is the position **in `string1`** of the first differing character; when the compared substrings are equal it is `end1`, which is what `string<=` / `string>=` / `string-not-greaterp` / `string-not-lessp` return for equal strings. The first five are case-sensitive; `string-lessp`, `string-greaterp`, `string-not-greaterp`, `string-not-lessp` and `string-not-equal` are the case-insensitive counterparts of `string<`, `string>`, `string<=`, `string>=` and `string/=`. Each argument is coerced with `string`, so a symbol or character designator is accepted. `:start1`/`:end1`/`:start2`/`:end2` bound the substrings actually compared, and the returned index stays absolute in `string1`.

```lisp
(list (string< "aaaa" "aaab")
      (string>= "aaaaa" "aaaa")
      (string-not-greaterp "Abcde" "abcdE")
      (string-lessp "012AAAA789" "01aaab6" :start1 3 :end1 7 :start2 2 :end2 6)) ; => (3 4 5 6)
```
