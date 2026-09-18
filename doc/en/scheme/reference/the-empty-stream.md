# the-empty-stream

`the-empty-stream`

A variable holding the empty stream, which is the empty list `'()`. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
the-empty-stream ; => ()
(stream-null? the-empty-stream) ; => #t
```
