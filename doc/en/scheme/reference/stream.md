# stream

`(stream obj ...)`

Returns a finite stream of its arguments, in order; with no arguments, the empty stream. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream->list (stream 1 2 3)) ; => (1 2 3)
(stream) ; => ()
```
