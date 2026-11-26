# read-error?

`(read-error? obj)`

Answers `#t` when `obj` is the error `read` raises on malformed input -- an unexpected `)`, an unclosed list or string. Any other object answers `#f`.

```scheme
(guard (e (#t (read-error? e))) (error "not a read error")) ; => #f
```
