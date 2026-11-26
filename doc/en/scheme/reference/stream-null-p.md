# stream-null?

`(stream-null? obj)`

Returns `#t` if `obj` is the empty stream, `'()`, and `#f` otherwise. It is the same test as `null?`. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-null? the-empty-stream) ; => #t
(stream-null? (stream 1)) ; => #f
```
