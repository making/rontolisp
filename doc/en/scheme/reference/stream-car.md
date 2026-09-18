# stream-car

`(stream-car stream)`

Returns the first element of a non-empty stream. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-car (stream 1 2 3)) ; => 1
(stream-car (cons-stream 'a '())) ; => a
```
