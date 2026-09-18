# stream-rest

`(stream-rest stream)`

The same as `stream-cdr`: the rest of a non-empty stream, forced once and remembered. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-head (stream-rest (stream 7 8 9)) 2) ; => (8 9)
```
