# string-copy

`(string-copy string)` `(string-copy string start)` `(string-copy string start end)`

Returns a newly allocated copy of `string`, or of the part from `start` (inclusive) to `end` (exclusive, default the end of the string).

```scheme
(string-copy "hello" 1) ; => "ello"
(string-copy "hello" 1 3) ; => "el"
```
