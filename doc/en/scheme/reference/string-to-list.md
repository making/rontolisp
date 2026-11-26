# string->list

`(string->list string)` `(string->list string start)` `(string->list string start end)`

Returns a list of the characters of `string`, or of the part from `start` (inclusive) to `end` (exclusive, default the end of the string).

```scheme
(string->list "abc") ; => (#\a #\b #\c)
(string->list "abcde" 1 3) ; => (#\b #\c)
```
