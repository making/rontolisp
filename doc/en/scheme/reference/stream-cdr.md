# stream-cdr

`(stream-cdr stream)`

Returns the rest of a non-empty stream, forcing its promise; the empty stream signals an error. The rest is computed the first time and remembered, so walking a stream twice evaluates each element once. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-car (stream-cdr (stream 1 2 3))) ; => 2
(stream-cdr (stream 1)) ; => ()
```
