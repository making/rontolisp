# empty-stream?

`(empty-stream? obj)`

The same as `stream-null?`: `#t` if `obj` is the empty stream, `'()`. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(empty-stream? (stream)) ; => #t
(empty-stream? (stream 1)) ; => #f
```
