# stream-first

`(stream-first stream)`

The same as `stream-car`: the first element of a non-empty stream. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-first (stream 7 8 9)) ; => 7
```
