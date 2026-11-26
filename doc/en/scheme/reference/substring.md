# substring

`(substring string start end)`

Returns a new string holding the characters of `string` from index `start` (inclusive) to `end` (exclusive). Both indexes are required.

```scheme
(substring "hello" 1 3) ; => "el"
(substring "hello" 0 5) ; => "hello"
```
